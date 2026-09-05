package map.service.user.recommend;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import map.service.user.global.crypto.PayloadCipher;
import com.fasterxml.jackson.databind.JsonNode;
import map.service.user.global.crypto.LocationSeal;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RecommendJobsConsumer — agent 완료 이벤트 스트림 컨슈머
 *
 * Redis Streams 의 추천 완료 이벤트를 수신하여 draft 를 저장하고 ack 한다.
 * StreamListener 의 MapRecord 콜백을 구현한다.
 *
 * 재시도/DLQ 모델 (Redis PEL 기반):
 *   StreamMessageListenerContainer 는 ReadOffset.lastConsumed()('>') 로
 *   "새 메시지" 만 수신하므로, 처리 실패로 ack 하지 않은 메시지는 컨슈머
 *   그룹의 PEL(Pending Entries List)에 남는다. 컨테이너가 PEL 을 자동
 *   재배달하지 않으므로, 별도 스케줄 작업 {@link #reclaimPending()} 이
 *   주기적으로 idle 경과 pending 을 XCLAIM 하여 재처리한다.
 *   - onMessage: 성공 시 save→ack, 실패 시 ack 하지 않고 PEL 에 남긴다.
 *   - reclaimPending: idle 이 min-idle 을 넘은 pending 을 회수한다. Redis 가
 *     관리하는 delivery count(getTotalDeliveryCount)가 maxRetry 를 초과하면
 *     DLQ 로 보내고 ack, 그렇지 않으면 XCLAIM(재배달 횟수 +1) 후 재처리한다.
 *   이로써 재시도 카운트가 인스턴스 메모리가 아닌 Redis 에 보관되어
 *   재시작/다중 인스턴스에서도 일관되며, 메모리 누수가 없다.
 *
 * 주요 필드:
 *   stream/group/consumer : 구독 스트림 키 / 컨슈머 그룹 / 컨슈머명(XCLAIM newOwner).
 *   dlqStream/dlqMaxlen   : DLQ 스트림 키 / 대략 최대 길이(>0 이면 XTRIM ~MAXLEN).
 *   maxRetry              : delivery count 가 이 값을 초과하면 DLQ 로 라우팅.
 *   minIdle               : 이 시간 이상 idle 인 pending 만 reclaim 대상.
 *   reclaimBatch          : 한 reclaim 라운드에서 조회할 pending 최대 건수.
 */
@Component
public class RecommendJobsConsumer
        implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger log = LoggerFactory.getLogger(RecommendJobsConsumer.class);

    private final DraftStore draftStore;
    private final RecommendJobStore jobStore;
    private final ReuseCacheStore reuseCacheStore;
    private final StringRedisTemplate streamsTemplate;
    private final String stream;
    private final String group;
    private final String consumer;
    private final PayloadCipher payloadCipher;
    private final LocationSeal seal;
    private final String dlqStream;
    private final int maxRetry;
    private final long dlqMaxlen;
    private final Duration minIdle;
    private final long reclaimBatch;

    /**
     * 의존성 주입 생성자.
     *
     * draftStore: draft 저장소.
     * jobStore: recommend_jobs PG write-through 저장소(완료 시 markFinished 기록).
     * streamsFactory: @Qualifier("streamsConnectionFactory") RedisConnectionFactory.
     * stream/group/consumer/dlqStream/maxRetry/dlqMaxlen: 위 필드 설명 참조.
     * minIdleMs: reclaim 대상 최소 idle(ms, 기본 60000). reclaimBatch: 라운드당 조회 수(기본 64).
     */
    public RecommendJobsConsumer(
            DraftStore draftStore,
            RecommendJobStore jobStore,
            ReuseCacheStore reuseCacheStore,
            @Qualifier("streamsConnectionFactory") RedisConnectionFactory streamsFactory,
            @Value("${streams.recommend-stream:agent:jobs:done}") String stream,
            @Value("${streams.recommend-group:bff-result}") String group,
            @Value("${streams.recommend-consumer:user-1}") String consumer,
            @Value("${streams.recommend-dlq-stream:agent:jobs:done:dlq}") String dlqStream,
            @Value("${streams.recommend-max-retry:3}") int maxRetry,
            @Value("${streams.recommend-dlq-maxlen:2000}") long dlqMaxlen,
            @Value("${streams.recommend-min-idle-ms:60000}") long minIdleMs,
            @Value("${streams.recommend-reclaim-batch:64}") long reclaimBatch,
            PayloadCipher payloadCipher,
            LocationSeal seal
    ) {
        this.payloadCipher = payloadCipher;
        this.seal = seal;
        this.draftStore = draftStore;
        this.jobStore = jobStore;
        this.reuseCacheStore = reuseCacheStore;
        this.streamsTemplate = new StringRedisTemplate(streamsFactory);
        this.stream = stream;
        this.group = group;
        this.consumer = consumer;
        this.dlqStream = dlqStream;
        this.maxRetry = maxRetry;
        this.dlqMaxlen = dlqMaxlen;
        this.minIdle = Duration.ofMillis(minIdleMs);
        this.reclaimBatch = reclaimBatch;
    }

    /**
     * 스트림 메시지 1건 처리 콜백 (컨테이너 및 reclaim 재처리에서 공용).
     *
     * MapRecord 의 value 에서 "job_id", "payload" 를 추출한다. 둘 중 하나라도
     * 없으면 경고 로그 후 ack(폐기). 정상 케이스는 DraftStore.save →
     * RecommendJobStore.markFinished(PG write-through) → ack. PG 기록은
     * best-effort 이며 실패해도 예외를 던지지 않아 ack 를 막지 않는다.
     * RuntimeException(예: draft save 실패) 시 ack 하지 않고 PEL 에 남긴다 —
     * 재시도/DLQ 는 {@link #reclaimPending()} 이 Redis delivery count 기반으로 처리한다.
     *
     * message: Redis Stream 의 MapRecord(키=stream, 값 맵).
     */
    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        String recordId = message.getId().getValue();
        Map<String, String> value = message.getValue();
        String jobId = value.get("job_id");
        // agent 는 본문을 감싸서 넣는다. 감싸지 않은 것도 그대로 받아 준다 —
        // 양쪽 배포 사이에 스트림에 남아 있던 옛 메시지를 버리지 않기 위해서다.
        String payloadJson = openIfSealed(value.get("payload"), jobId);

        if (jobId == null || payloadJson == null) {
            // 그룹을 만들려면 스트림이 있어야 해서, 없을 때 자리표시 한 건을
            // 넣어 두고 만든다(StreamsConsumerConfig). 그 한 건이 여기로 온다.
            // 정상 동작이므로 경고로 남기지 않는다 — 새로 띄울 때마다 경고가
            // 뜨면 진짜 경고를 함께 흘려 보게 된다.
            if (value.containsKey("_init")) {
                log.info("stream placeholder discarded id={}", recordId);
            } else {
                log.warn("Stream message missing job_id or payload id={}", recordId);
            }
            ack(recordId);
            return;
        }

        try {
            // 1) 초안 먼저. 사용자가 결과를 보는 경로라 여기서 막히면 안 된다.
            //    같은 값을 다시 써도 되므로 재처리에 안전하다.
            draftStore.save(jobId, payloadJson);

            // 2) 영속 기록. **실패하면 예외가 올라와 ack 하지 않는다.**
            //    예전에는 이 기록이 실패를 삼켜서, 기록이 안 됐는데도 ack 되어
            //    메시지가 사라졌다. 사용자는 초안으로 결과를 이미 받았으므로
            //    아무도 눈치채지 못한 채 학습 신호만 조용히 없어졌다.
            jobStore.recordCompletion(
                    jobId, value.get("status"), payloadJson, value.get("training"));

            // 3) 재사용 캐시. 영속 기록이 끝난 뒤에 한다 — 연결고리를 읽으면서
            //    지우기 때문에(GETDEL), 앞 단계가 실패해 재처리될 때 이미
            //    소비돼 있으면 캐시가 영영 갱신되지 않는다.
            updateReuseCacheIfLinked(jobId, payloadJson, value.get("status"));

            ack(recordId);
            log.info("draft saved job_id={} stream_id={} training={}",
                    jobId, recordId, value.get("training") != null);
        } catch (RuntimeException e) {
            // ack 하지 않고 PEL 에 남긴다. reclaimPending() 이 idle 경과 후
            // XCLAIM 으로 재처리(재배달 횟수 +1)하고, maxRetry 초과 시 DLQ 로 보낸다.
            log.error("job completion persist failed job_id={} stream_id={} reason={} "
                    + "(left pending for reclaim)", jobId, recordId, e.getMessage());
        }
    }

    /**
     * jobId 가 재사용 캐시와 연결돼 있으면(=미스 또는 백그라운드 강제 갱신으로 생성된
     * job) 캐시 본체를 이번 payload 로 덮어쓰고, 히트 카운터 TTL 도 함께 연장한다
     * (계속 사용되는 캐시의 카운터가 본체보다 먼저 만료되지 않도록). 연결이 없으면
     * (=진짜 히트로 생성된 job, agent 를 안 거쳤음) 아무 것도 하지 않는다. 조회/저장 중
     * 오류가 나도 조용히 무시한다 — 캐시 갱신 실패가 draft 저장·ack 를 막아서는 안 된다
     * (무중단 원칙).
     *
     * status 가 "done" 이 아닌 payload 는 캐시에 넣지 않는다. 실패 결과를 캐시하면
     * 같은 조건의 후속 요청이 캐시 TTL(기본 7일) 내내 실패 응답을 재사용하게 되어,
     * 일시적 실패가 장기 장애로 굳는다. 연결고리는 이미 소비(GETDEL)됐으므로
     * 다음 요청은 캐시 미스로 정상 재시도한다.
     */
    private void updateReuseCacheIfLinked(String jobId, String payloadJson, String status) {
        try {
            reuseCacheStore.consumeLink(jobId)
                    .ifPresent(hash -> {
                        // 만드는 중 표시는 성공이든 실패든 치운다. 실패했는데
                        // 그대로 두면 시한이 끝날 때까지 같은 조건의 모든 요청이
                        // 나오지 않을 결과를 기다린다.
                        reuseCacheStore.releaseProducer(hash);
                        if (!"done".equals(status)) {
                            log.warn("reuse cache skipped for non-done job job_id={} status={}",
                                    jobId, status);
                            return;
                        }
                        reuseCacheStore.save(hash, payloadJson);
                        reuseCacheStore.renewHitsTtl(hash);
                    });
        } catch (RuntimeException e) {
            log.warn("reuse cache update failed job_id={} reason={}", jobId, e.getMessage());
        }
    }

    /**
     * PEL reclaimer — idle 경과한 미처리(pending) 메시지를 주기적으로 회수.
     *
     * XPENDING 으로 pending 을 조회하고, 마지막 배달 후 경과가 min-idle 이상인
     * 항목만 처리한다. Redis delivery count 가 maxRetry 를 초과하면 DLQ 로
     * 라우팅 후 ack, 아니면 XCLAIM(재배달 횟수 +1) 후 onMessage 로 재처리한다.
     * 주기는 streams.recommend-reclaim-interval-ms(기본 30000) 로 설정한다.
     */
    @Scheduled(fixedDelayString = "${streams.recommend-reclaim-interval-ms:30000}")
    public void reclaimPending() {
        PendingMessages pending;
        try {
            pending = streamsTemplate.opsForStream()
                    .pending(stream, group, Range.unbounded(), reclaimBatch);
        } catch (RuntimeException e) {
            log.warn("reclaim pending query failed stream={} group={} reason={}",
                    stream, group, e.getMessage());
            return;
        }
        for (PendingMessage pm : pending) {
            if (pm.getElapsedTimeSinceLastDelivery().compareTo(minIdle) < 0) {
                continue;
            }
            try {
                if (pm.getTotalDeliveryCount() > maxRetry) {
                    exhaustToDlq(pm);
                } else {
                    reclaimAndReprocess(pm);
                }
            } catch (RuntimeException e) {
                log.warn("reclaim handling failed id={} reason={}",
                        pm.getIdAsString(), e.getMessage());
            }
        }
    }

    /**
     * pending 메시지를 XCLAIM(재배달 횟수 +1)하여 onMessage 로 재처리한다.
     * 재처리 성공 시 onMessage 가 ack, 실패 시 다시 PEL 에 남아 다음 라운드 대상.
     */
    private void reclaimAndReprocess(PendingMessage pm) {
        List<MapRecord<String, String, String>> claimed =
                streamsTemplate.<String, String>opsForStream()
                        .claim(stream, group, consumer, minIdle, pm.getId());
        for (MapRecord<String, String, String> rec : claimed) {
            onMessage(rec);
        }
    }

    /**
     * maxRetry 초과 pending 을 XCLAIM 으로 본문을 확보해 DLQ 로 보내고 ack 한다.
     * claim 이 비면(사이에 다른 처리로 사라짐) 무시한다.
     */
    private void exhaustToDlq(PendingMessage pm) {
        List<MapRecord<String, String, String>> claimed =
                streamsTemplate.<String, String>opsForStream()
                        .claim(stream, group, consumer, minIdle, pm.getId());
        if (claimed.isEmpty()) {
            return;
        }
        MapRecord<String, String, String> rec = claimed.get(0);
        Map<String, String> value = rec.getValue();
        long deliveries = pm.getTotalDeliveryCount();
        // 대기열에는 한 겹만 씌운다. 들어온 값이 감싸여 있으면 먼저 열고 저장
        // 암호화로 다시 감싼다. 두 겹으로 두면 되살리는 쪽이 어느 것부터
        // 풀어야 하는지 알 수 없다.
        routeToDlq(rec, value.get("job_id"),
                openIfSealed(value.get("payload"), value.get("job_id")),
                value.get("status"),
                value.get("training"), (int) deliveries,
                "max retries exceeded (" + deliveries + " deliveries)");
        ack(rec.getId().getValue());
    }

    /**
     * 메시지에 대해 XACK 호출. ack 자체 예외는 경고 로그만 남기고 삼킨다.
     */
    private void ack(String recordId) {
        try {
            streamsTemplate.opsForStream().acknowledge(
                    stream, group, RecordId.of(recordId));
        } catch (RuntimeException ackError) {
            log.warn("ack failed stream={} group={} id={} reason={}",
                    stream, group, recordId, ackError.getMessage());
        }
    }

    /**
     * 메시지를 DLQ 스트림으로 전달.
     *
     * dlqEntry 에 job_id/status/payload/original_id/delivery_count/error 키를 담아 XADD 한다.
     * dlqMaxlen > 0 이면 XTRIM ~MAXLEN(approximate=true) 로 길이를 제한한다.
     * trim/publish 자체의 예외는 로그만 남기고 삼킨다.
     */
    /**
     * 실패한 본문을 묶어 둘 자리 이름. 이 스트림은 최근 2000건을 계속 들고
     * 있으므로, 감싸지 않으면 실패한 요청의 본문이 그만큼 쌓여 남는다.
     */
    private static String dlqAad(String jobId) {
        return PayloadCipher.aad("redis", "agent:jobs:done:dlq", jobId);
    }

    /**
     * 감싸서 온 본문을 연다. 감싸지 않은 값은 그대로 돌려준다.
     *
     * 열지 못하면 null 을 돌려주어 이 메시지를 버린다. 열지 못한 값을 그대로
     * 저장하면 화면이 알아볼 수 없는 문자열을 일정으로 받게 되는데, 그때는
     * 사용자에게 빈 일정으로 보여 원인이 드러나지 않는다.
     */
    private String openIfSealed(String raw, String jobId) {
        if (raw == null || !seal.isSealed(raw)) {
            return raw;
        }
        try {
            JsonNode opened = seal.open(raw);
            JsonNode body = opened.get("payload");
            return body == null ? null : body.asText();
        } catch (RuntimeException e) {
            log.error("cannot open sealed payload job_id={} reason={}",
                    jobId, e.getMessage());
            return null;
        }
    }

    private void routeToDlq(
            MapRecord<String, String, String> message,
            String jobId,
            String payloadJson,
            String status,
            String trainingJson,
            int deliveryCount,
            String error
    ) {
        try {
            // 고정 목록 대신 맵을 쌓는 이유: 학습 신호는 없을 수도 있는데
            // Map.of 는 null 을 받지 않는다. 그리고 이 신호를 여기서 빠뜨리면
            // 재처리 못 한 잡의 후보 목록이 통째로 사라진다 — DLQ 로 보내는
            // 목적이 나중에 되살리는 것이므로 본문은 전부 남겨야 한다.
            Map<String, String> dlqEntry = new HashMap<>();
            dlqEntry.put("job_id", jobId != null ? jobId : "unknown");
            dlqEntry.put("status", status != null ? status : "unknown");
            dlqEntry.put("payload", payloadJson != null
                    ? payloadCipher.encrypt(payloadJson, dlqAad(jobId)) : "");
            dlqEntry.put("original_id", message.getId().getValue());
            dlqEntry.put("delivery_count", String.valueOf(deliveryCount));
            dlqEntry.put("error", error);
            if (trainingJson != null) {
                dlqEntry.put("training", trainingJson);
            }
            MapRecord<String, String, String> dlqRecord =
                    StreamRecords.mapBacked(dlqEntry).withStreamKey(dlqStream);
            streamsTemplate.opsForStream().add(dlqRecord);
            if (dlqMaxlen > 0) {
                try {
                    streamsTemplate.opsForStream().trim(dlqStream, dlqMaxlen, true);
                } catch (RuntimeException trimError) {
                    log.warn("dlq trim failed stream={} reason={}",
                            dlqStream, trimError.getMessage());
                }
            }
            log.warn("routed to dlq stream={} original_id={} job_id={} attempts={}",
                    dlqStream, message.getId().getValue(), jobId, deliveryCount);
        } catch (RuntimeException dlqError) {
            log.error("dlq publish failed original_id={} job_id={} reason={}",
                    message.getId().getValue(), jobId, dlqError.getMessage(), dlqError);
        }
    }
}

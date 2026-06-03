package map.service.user.recommend;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final StringRedisTemplate streamsTemplate;
    private final String stream;
    private final String group;
    private final String consumer;
    private final String dlqStream;
    private final int maxRetry;
    private final long dlqMaxlen;
    private final Duration minIdle;
    private final long reclaimBatch;

    /**
     * 의존성 주입 생성자.
     *
     * draftStore: draft 저장소.
     * streamsFactory: @Qualifier("streamsConnectionFactory") RedisConnectionFactory.
     * stream/group/consumer/dlqStream/maxRetry/dlqMaxlen: 위 필드 설명 참조.
     * minIdleMs: reclaim 대상 최소 idle(ms, 기본 60000). reclaimBatch: 라운드당 조회 수(기본 64).
     */
    public RecommendJobsConsumer(
            DraftStore draftStore,
            @Qualifier("streamsConnectionFactory") RedisConnectionFactory streamsFactory,
            @Value("${streams.recommend-stream:agent:jobs:done}") String stream,
            @Value("${streams.recommend-group:bff-result}") String group,
            @Value("${streams.recommend-consumer:user-1}") String consumer,
            @Value("${streams.recommend-dlq-stream:agent:jobs:done:dlq}") String dlqStream,
            @Value("${streams.recommend-max-retry:3}") int maxRetry,
            @Value("${streams.recommend-dlq-maxlen:2000}") long dlqMaxlen,
            @Value("${streams.recommend-min-idle-ms:60000}") long minIdleMs,
            @Value("${streams.recommend-reclaim-batch:64}") long reclaimBatch
    ) {
        this.draftStore = draftStore;
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
     * 없으면 경고 로그 후 ack(폐기). 정상 케이스는 DraftStore.save → ack.
     * RuntimeException 발생 시 ack 하지 않고 PEL 에 남긴다 — 재시도/DLQ 는
     * {@link #reclaimPending()} 이 Redis delivery count 기반으로 처리한다.
     *
     * message: Redis Stream 의 MapRecord(키=stream, 값 맵).
     */
    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        String recordId = message.getId().getValue();
        Map<String, String> value = message.getValue();
        String jobId = value.get("job_id");
        String payloadJson = value.get("payload");

        if (jobId == null || payloadJson == null) {
            log.warn("Stream message missing job_id or payload id={}", recordId);
            ack(recordId);
            return;
        }

        try {
            draftStore.save(jobId, payloadJson);
            ack(recordId);
            log.info("draft saved job_id={} stream_id={}", jobId, recordId);
        } catch (RuntimeException e) {
            // ack 하지 않고 PEL 에 남긴다. reclaimPending() 이 idle 경과 후
            // XCLAIM 으로 재처리(재배달 횟수 +1)하고, maxRetry 초과 시 DLQ 로 보낸다.
            log.error("draft save failed job_id={} stream_id={} reason={} "
                    + "(left pending for reclaim)", jobId, recordId, e.getMessage());
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
        routeToDlq(rec, value.get("job_id"), value.get("payload"), value.get("status"),
                (int) deliveries,
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
    private void routeToDlq(
            MapRecord<String, String, String> message,
            String jobId,
            String payloadJson,
            String status,
            int deliveryCount,
            String error
    ) {
        try {
            Map<String, String> dlqEntry = Map.of(
                    "job_id", jobId != null ? jobId : "unknown",
                    "status", status != null ? status : "unknown",
                    "payload", payloadJson != null ? payloadJson : "",
                    "original_id", message.getId().getValue(),
                    "delivery_count", String.valueOf(deliveryCount),
                    "error", error
            );
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

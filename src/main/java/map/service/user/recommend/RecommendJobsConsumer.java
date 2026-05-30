package map.service.user.recommend;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

/**
 * RecommendJobsConsumer — agent 완료 이벤트 스트림 컨슈머
 *
 * Redis Streams 의 추천 완료 이벤트를 수신하여 draft 를 저장하고 ack 한다.
 * StreamListener 의 MapRecord 콜백을 구현한다.
 * 처리 실패 시 메모리 카운터로 재시도 횟수를 누적하다 한도를 넘으면 DLQ 스트림으로 전달한다.
 *
 * log: 슬프 로거.
 * draftStore: DraftStore. 수신한 payload 를 draft 로 저장.
 * streamsTemplate: streams 전용 RedisConnectionFactory 로 만든 StringRedisTemplate.
 *                  ack/add/trim 에 사용.
 * stream: 구독 대상 스트림 키 (streams.recommend-stream, 기본 "agent:jobs:done").
 * group: 컨슈머 그룹 (streams.recommend-group, 기본 "bff-result").
 * dlqStream: DLQ 대상 스트림 키 (streams.recommend-dlq-stream, 기본 "agent:jobs:done:dlq").
 * maxRetry: 동일 메시지 재시도 허용 횟수 (streams.recommend-max-retry, 기본 3).
 *           초과 시 DLQ 로 라우팅 후 ack.
 * dlqMaxlen: DLQ 스트림의 대략적 최대 길이 (streams.dlq-maxlen, 기본 2000).
 *            >0 인 경우 XTRIM ~MAXLEN 로 잘라낸다.
 * deliveryCounts: recordId → 재시도 횟수 ConcurrentHashMap. ack 이후 제거된다.
 */
@Component
public class RecommendJobsConsumer
        implements StreamListener<String, MapRecord<String, String, String>> {

    private static final Logger log = LoggerFactory.getLogger(RecommendJobsConsumer.class);

    private final DraftStore draftStore;
    private final StringRedisTemplate streamsTemplate;
    private final String stream;
    private final String group;
    private final String dlqStream;
    private final int maxRetry;
    private final long dlqMaxlen;

    private final Map<String, Integer> deliveryCounts = new ConcurrentHashMap<>();

    /**
     * 의존성 주입 생성자.
     *
     * draftStore: draft 저장소.
     * streamsFactory: @Qualifier("streamsConnectionFactory") RedisConnectionFactory.
     *                 streams 전용 연결 풀에서 StringRedisTemplate 을 생성한다.
     * stream / group / dlqStream / maxRetry / dlqMaxlen: 위 필드 설명 참조.
     */
    public RecommendJobsConsumer(
            DraftStore draftStore,
            @Qualifier("streamsConnectionFactory") RedisConnectionFactory streamsFactory,
            @Value("${streams.recommend-stream:agent:jobs:done}") String stream,
            @Value("${streams.recommend-group:bff-result}") String group,
            @Value("${streams.recommend-dlq-stream:agent:jobs:done:dlq}") String dlqStream,
            @Value("${streams.recommend-max-retry:3}") int maxRetry,
            @Value("${streams.dlq-maxlen:2000}") long dlqMaxlen
    ) {
        this.draftStore = draftStore;
        this.streamsTemplate = new StringRedisTemplate(streamsFactory);
        this.stream = stream;
        this.group = group;
        this.dlqStream = dlqStream;
        this.maxRetry = maxRetry;
        this.dlqMaxlen = dlqMaxlen;
    }

    /**
     * 스트림 메시지 1건 처리 콜백.
     *
     * MapRecord 의 value 에서 "job_id", "payload", "status" 를 추출한다.
     * job_id 또는 payload 가 없으면 경고 로그 후 ack 만 처리하고 종료.
     * 정상 케이스에서는 DraftStore.save → ack 순으로 처리한다.
     * RuntimeException 발생 시 deliveryCounts 를 1 증가시키고 maxRetry 초과면 DLQ 로 보낸 뒤 ack.
     * maxRetry 이내이면 ack 하지 않아 스트림이 재배달하도록 둔다.
     *
     * message: Redis Stream 의 MapRecord(키=stream, 값 맵).
     */
    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        String recordId = message.getId().getValue();
        Map<String, String> value = message.getValue();
        String jobId = value.get("job_id");
        String payloadJson = value.get("payload");
        String status = value.get("status");

        if (jobId == null || payloadJson == null) {
            log.warn("Stream message missing job_id or payload id={}", recordId);
            ackAndForget(recordId);
            return;
        }

        try {
            draftStore.save(jobId, payloadJson);
            ackAndForget(recordId);
            log.info("draft saved job_id={} stream_id={}", jobId, recordId);
        } catch (RuntimeException e) {
            int count = deliveryCounts.merge(recordId, 1, Integer::sum);
            log.error("draft save failed job_id={} stream_id={} attempt={}/{} reason={}",
                    jobId, recordId, count, maxRetry, e.getMessage());
            if (count > maxRetry) {
                routeToDlq(message, jobId, payloadJson, status, count, e);
                ackAndForget(recordId);
            }
        }
    }

    /**
     * 메시지에 대해 XACK 호출 후 재시도 카운터를 제거.
     *
     * ack 자체에서 예외가 나도 경고 로그만 남기고 삼킨다.
     *
     * recordId: 처리한 stream record id.
     */
    private void ackAndForget(String recordId) {
        try {
            streamsTemplate.opsForStream().acknowledge(
                    stream, group, RecordId.of(recordId));
        } catch (RuntimeException ackError) {
            log.warn("ack failed stream={} group={} id={} reason={}",
                    stream, group, recordId, ackError.getMessage());
        }
        deliveryCounts.remove(recordId);
    }

    /**
     * maxRetry 초과 메시지를 DLQ 스트림으로 전달.
     *
     * dlqEntry 에 job_id/status/payload/original_id/delivery_count/error 키를 포함시켜 XADD 한다.
     * dlqMaxlen > 0 이면 XTRIM ~MAXLEN(approximate=true) 로 길이를 제한한다.
     * trim/publish 자체의 예외는 로그만 남기고 삼킨다.
     *
     * message: 원본 MapRecord.
     * jobId / payloadJson / status: 원본 메시지에서 추출한 필드.
     * deliveryCount: 누적 재시도 횟수.
     * cause: 마지막 실패 원인.
     */
    private void routeToDlq(
            MapRecord<String, String, String> message,
            String jobId,
            String payloadJson,
            String status,
            int deliveryCount,
            RuntimeException cause
    ) {
        try {
            Map<String, String> dlqEntry = Map.of(
                    "job_id", jobId,
                    "status", status != null ? status : "unknown",
                    "payload", payloadJson,
                    "original_id", message.getId().getValue(),
                    "delivery_count", String.valueOf(deliveryCount),
                    "error", cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName()
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

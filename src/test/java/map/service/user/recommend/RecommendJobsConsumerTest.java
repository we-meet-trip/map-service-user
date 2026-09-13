package map.service.user.recommend;

import map.service.user.global.crypto.TestPayloadCiphers;
import map.service.user.global.crypto.TestLocationSeals;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.test.util.ReflectionTestUtils;

class RecommendJobsConsumerTest {

    private DraftStore draftStore;
    private RecommendJobStore jobStore;
    private ReuseCacheStore reuseCacheStore;
    private RecommendJobsConsumer consumer;
    private StringRedisTemplate streams;
    private StreamOperations<String, String, String> operations;

    @BeforeEach
    void setUp() {
        draftStore = mock(DraftStore.class);
        jobStore = mock(RecommendJobStore.class);
        when(jobStore.recordWorkerCompletion(any(), any(), any(), any())).thenReturn(true);
        reuseCacheStore = mock(ReuseCacheStore.class);
        RedisConnectionFactory streamsFactory = mock(RedisConnectionFactory.class);
        consumer = new RecommendJobsConsumer(
                draftStore, jobStore, reuseCacheStore, streamsFactory,
                "agent:jobs:done", "bff-result", "user-1",
                "agent:jobs:done:dlq", 3, 2000L, 60000L, 64L,
                TestPayloadCiphers.enabled(), TestLocationSeals.enabled());
        streams = mock(StringRedisTemplate.class);
        operations = mock(StreamOperations.class);
        when(streams.<String, String>opsForStream()).thenReturn(operations);
        ReflectionTestUtils.setField(consumer, "streamsTemplate", streams);
    }

    private MapRecord<String, String, String> record(String jobId, String payload) {
        return record(jobId, payload, "done");
    }

    private MapRecord<String, String, String> record(
            String jobId, String payload, String status) {
        return StreamRecords.mapBacked(
                        Map.of("job_id", jobId, "payload", payload, "status", status))
                .withStreamKey("agent:jobs:done")
                .withId(RecordId.of("1-1"));
    }

    @Test
    void 영속기록이_실패하면_연결고리를_소비하지_않는다() {
        // 연결고리는 읽으면서 지운다(GETDEL). 영속 기록보다 먼저 소비해 버리면,
        // 기록이 실패해 메시지가 재처리될 때 연결고리가 이미 없어 재사용 캐시가
        // 영영 갱신되지 않는다. 그래서 순서가 기록 → 캐시여야 한다.
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(jobStore).recordWorkerCompletion(any(), any(), any(), any());

        consumer.onMessage(record("job-9", "{\"places\":[]}"));

        verify(reuseCacheStore, never()).consumeLink(any());
        verify(reuseCacheStore, never()).save(any(), any());
        verify(streams, never()).execute(org.mockito.ArgumentMatchers.eq(RecommendJobsConsumer.ACK_AND_DELETE), org.mockito.ArgumentMatchers.eq(List.of("agent:jobs:done")), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 영속기록이_실패하면_초안을_공개하지_않는다() {
        // 사용자가 결과를 보는 경로는 초안이다. 기록 실패가 결과 전달까지
        // 막아서는 안 된다.
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(jobStore).recordWorkerCompletion(any(), any(), any(), any());

        consumer.onMessage(record("job-9", "{\"places\":[]}"));

        verify(draftStore, never()).save(any(), any());
    }

    @Test
    void 학습신호가_영속기록으로_함께_넘어간다() {
        MapRecord<String, String, String> rec = StreamRecords.mapBacked(
                        Map.of("job_id", "job-t", "payload", "{\"places\":[]}",
                                "status", "done",
                                "training", "{\"schema_version\":1,\"path\":\"select\"}"))
                .withStreamKey("agent:jobs:done")
                .withId(RecordId.of("1-1"));

        consumer.onMessage(rec);

        verify(jobStore).recordWorkerCompletion("job-t", "done", "{\"places\":[]}",
                "{\"schema_version\":1,\"path\":\"select\"}");
    }

    @Test
    void 학습신호가_없어도_처리된다() {
        // agent 가 옛 판이거나 route·저하 경로면 이 필드가 없다.
        consumer.onMessage(record("job-n", "{\"places\":[]}"));

        verify(jobStore).recordWorkerCompletion("job-n", "done", "{\"places\":[]}", null);
    }

    @Test
    void savesDraftAndUpdatesCacheWhenLinkPresent() {
        when(reuseCacheStore.consumeLink("job-1")).thenReturn(Optional.of("hash-abc"));

        consumer.onMessage(record("job-1", "{\"places\":[]}"));

        verify(draftStore).save("job-1", "{\"places\":[]}");
        verify(reuseCacheStore).save("hash-abc", "{\"places\":[]}");
        verify(reuseCacheStore).renewHitsTtl("hash-abc");
    }

    @Test
    void doesNotTouchCacheWhenNoLinkPresent() {
        when(reuseCacheStore.consumeLink("job-2")).thenReturn(Optional.empty());

        consumer.onMessage(record("job-2", "{\"places\":[]}"));

        verify(draftStore).save("job-2", "{\"places\":[]}");
        verify(reuseCacheStore, never()).save(any(), any());
        verify(reuseCacheStore, never()).renewHitsTtl(any());
    }

    @Test
    void doesNotCacheFailedPayloadEvenWhenLinkPresent() {
        // 실패 결과를 캐시하면 같은 조건의 후속 요청이 캐시 TTL(기본 7일) 내내
        // 실패를 재사용해 일시적 실패가 장기 장애로 굳는다. 연결고리는 소비하되
        // 캐시 본체는 갱신하지 않아야 한다.
        when(reuseCacheStore.consumeLink("job-4")).thenReturn(Optional.of("hash-fail"));

        consumer.onMessage(record("job-4", "{\"status\":\"failed\"}", "failed"));

        verify(draftStore).save("job-4", "{\"status\":\"failed\"}");
        verify(reuseCacheStore).consumeLink("job-4");
        verify(reuseCacheStore, never()).save(any(), any());
        verify(reuseCacheStore, never()).renewHitsTtl(any());
    }

    @Test
    void cacheLookupFailureDoesNotPreventDraftSave() {
        when(reuseCacheStore.consumeLink("job-3")).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> consumer.onMessage(record("job-3", "{\"places\":[]}")))
                .doesNotThrowAnyException();

        verify(draftStore).save("job-3", "{\"places\":[]}");
    }
    @Test
    void cancelledProducerNotifiesWaitersWithoutPersistingPayload() {
        when(jobStore.isCancelled("cancelled-job")).thenReturn(true);
        consumer.onMessage(record("cancelled-job", "{\"status\":\"done\",\"places\":[]}"));
        verify(reuseCacheStore).cancelProducer("cancelled-job");
        verify(jobStore, never()).recordWorkerCompletion(any(), any(), any(), any());
        verify(draftStore, never()).save(any(), any());
        verify(streams).execute(RecommendJobsConsumer.ACK_AND_DELETE, List.of("agent:jobs:done"), "bff-result", "1-1");
    }

    @Test
    void completedPayloadIsAcknowledgedAndErasedAfterDurableStorage() {
        consumer.onMessage(record("job-1", "{\"places\":[]}"));
        var order = org.mockito.Mockito.inOrder(jobStore, streams);
        order.verify(jobStore).recordWorkerCompletion("job-1", "done", "{\"places\":[]}", null);
        order.verify(streams).execute(RecommendJobsConsumer.ACK_AND_DELETE, List.of("agent:jobs:done"), "bff-result", "1-1");
    }

    private void exhaustedPending() {
        PendingMessage message = mock(PendingMessage.class);
        when(message.getId()).thenReturn(RecordId.of("1-1"));
        when(message.getTotalDeliveryCount()).thenReturn(4L);
        when(message.getElapsedTimeSinceLastDelivery()).thenReturn(Duration.ofMinutes(2));
        PendingMessages pending = mock(PendingMessages.class);
        when(pending.iterator()).thenReturn(List.of(message).iterator());
        when(operations.pending(org.mockito.ArgumentMatchers.eq("agent:jobs:done"), org.mockito.ArgumentMatchers.eq("bff-result"), org.mockito.ArgumentMatchers.<org.springframework.data.domain.Range<String>>any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(pending);
        when(operations.claim("agent:jobs:done", "bff-result", "user-1", Duration.ofMinutes(1), RecordId.of("1-1")))
                .thenReturn(List.of(record("job-1", "{\"places\":[]}")));
    }

    @Test
    void dlqWriteFailureKeepsOriginalPendingPayload() {
        exhaustedPending();
        when(operations.add(any(MapRecord.class))).thenThrow(new RuntimeException("unavailable"));
        consumer.reclaimPending();
        verify(streams, never()).execute(org.mockito.ArgumentMatchers.eq(RecommendJobsConsumer.ACK_AND_DELETE), org.mockito.ArgumentMatchers.eq(List.of("agent:jobs:done")), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void dlqSuccessfulWriteAllowsOriginalErasure() {
        exhaustedPending();
        when(operations.add(any(MapRecord.class))).thenReturn(RecordId.of("2-1"));
        consumer.reclaimPending();
        verify(streams).execute(RecommendJobsConsumer.ACK_AND_DELETE, List.of("agent:jobs:done"), "bff-result", "1-1");
    }

    @Test
    void erasureRetriesCancelledJobsAndExpiresOnlyOldDlqBodies() {
        long now = System.currentTimeMillis();
        var cancelled = record("cancelled", "{}").withId(RecordId.of(now + "-1"));
        var live = record("live", "{}").withId(RecordId.of(now + "-2"));
        var old = record("expired", "{}").withId(RecordId.of((now - Duration.ofDays(2).toMillis()) + "-1"));
        when(operations.range(org.mockito.ArgumentMatchers.eq("agent:jobs:done"), any())).thenReturn(List.of(cancelled, live));
        when(operations.range(org.mockito.ArgumentMatchers.eq("agent:jobs:done:dlq"), any())).thenReturn(List.of(cancelled, live, old));
        when(jobStore.cancelledAmong(any())).thenReturn(Set.of("cancelled"));
        consumer.eraseExpiredPayloads();
        verify(streams).execute(RecommendJobsConsumer.ACK_AND_DELETE, List.of("agent:jobs:done"), "bff-result", cancelled.getId().getValue());
        verify(operations).delete("agent:jobs:done:dlq", cancelled.getId());
        verify(operations).delete("agent:jobs:done:dlq", old.getId());
        verify(operations, never()).delete("agent:jobs:done:dlq", live.getId());
    }

    @Test
    void expiredDlqIsDeletedEvenWhenCancellationDatabaseIsUnavailable() {
        long now = System.currentTimeMillis();
        var old = record("expired", "{}").withId(RecordId.of((now - Duration.ofDays(2).toMillis()) + "-1"));
        var fresh = record("fresh", "{}").withId(RecordId.of(now + "-1"));
        when(operations.range(org.mockito.ArgumentMatchers.eq("agent:jobs:done"), any())).thenReturn(List.of(fresh));
        when(operations.range(org.mockito.ArgumentMatchers.eq("agent:jobs:done:dlq"), any())).thenReturn(List.of(old, fresh));
        when(jobStore.cancelledAmong(any())).thenThrow(new IllegalStateException("database unavailable"));

        consumer.eraseExpiredPayloads();

        verify(operations).delete("agent:jobs:done:dlq", old.getId());
        verify(operations, never()).delete("agent:jobs:done:dlq", fresh.getId());
        verify(streams, never()).execute(org.mockito.ArgumentMatchers.<org.springframework.data.redis.core.script.RedisScript<Long>>any(),
                org.mockito.ArgumentMatchers.<String>anyList(), org.mockito.ArgumentMatchers.<Object[]>any());
    }

}

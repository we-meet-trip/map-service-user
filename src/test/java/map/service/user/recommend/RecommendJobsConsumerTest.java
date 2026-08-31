package map.service.user.recommend;

import map.service.user.global.crypto.TestPayloadCiphers;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;

class RecommendJobsConsumerTest {

    private DraftStore draftStore;
    private RecommendJobStore jobStore;
    private ReuseCacheStore reuseCacheStore;
    private RecommendJobsConsumer consumer;

    @BeforeEach
    void setUp() {
        draftStore = mock(DraftStore.class);
        jobStore = mock(RecommendJobStore.class);
        reuseCacheStore = mock(ReuseCacheStore.class);
        RedisConnectionFactory streamsFactory = mock(RedisConnectionFactory.class);
        consumer = new RecommendJobsConsumer(
                draftStore, jobStore, reuseCacheStore, streamsFactory,
                "agent:jobs:done", "bff-result", "user-1",
                "agent:jobs:done:dlq", 3, 2000L, 60000L, 64L,
                TestPayloadCiphers.enabled());
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
}

package map.service.user.recommend;

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
    private ReuseCacheStore reuseCacheStore;
    private RecommendJobsConsumer consumer;

    @BeforeEach
    void setUp() {
        draftStore = mock(DraftStore.class);
        reuseCacheStore = mock(ReuseCacheStore.class);
        RedisConnectionFactory streamsFactory = mock(RedisConnectionFactory.class);
        consumer = new RecommendJobsConsumer(
                draftStore, reuseCacheStore, streamsFactory,
                "agent:jobs:done", "bff-result", "user-1",
                "agent:jobs:done:dlq", 3, 2000L, 60000L, 64L);
    }

    private MapRecord<String, String, String> record(String jobId, String payload) {
        return StreamRecords.mapBacked(Map.of("job_id", jobId, "payload", payload))
                .withStreamKey("agent:jobs:done")
                .withId(RecordId.of("1-1"));
    }

    @Test
    void savesDraftAndUpdatesCacheWhenLinkPresent() {
        when(reuseCacheStore.consumeLink("job-1")).thenReturn(Optional.of("hash-abc"));

        consumer.onMessage(record("job-1", "{\"places\":[]}"));

        verify(draftStore).save("job-1", "{\"places\":[]}");
        verify(reuseCacheStore).save("hash-abc", "{\"places\":[]}");
    }

    @Test
    void doesNotTouchCacheWhenNoLinkPresent() {
        when(reuseCacheStore.consumeLink("job-2")).thenReturn(Optional.empty());

        consumer.onMessage(record("job-2", "{\"places\":[]}"));

        verify(draftStore).save("job-2", "{\"places\":[]}");
        verify(reuseCacheStore, never()).save(any(), any());
    }

    @Test
    void cacheLookupFailureDoesNotPreventDraftSave() {
        when(reuseCacheStore.consumeLink("job-3")).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> consumer.onMessage(record("job-3", "{\"places\":[]}")))
                .doesNotThrowAnyException();

        verify(draftStore).save("job-3", "{\"places\":[]}");
    }
}

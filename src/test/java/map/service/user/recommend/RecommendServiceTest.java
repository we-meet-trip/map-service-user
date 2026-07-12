package map.service.user.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import map.service.user.recommend.dto.DateRange;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.Mobility;
import map.service.user.recommend.dto.RecommendRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecommendServiceTest {

    @Mock
    private AgentClient agentClient;
    @Mock
    private DraftStore draftStore;
    @Mock
    private ReuseCacheStore reuseCacheStore;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecommendCacheKey cacheKeyBuilder = new RecommendCacheKey(50_000, 60);
    private final Executor immediateExecutor = Runnable::run;

    private RecommendService service;
    private RecommendRequest request;

    @BeforeEach
    void setUp() {
        service = new RecommendService(
                agentClient, draftStore, objectMapper,
                reuseCacheStore, cacheKeyBuilder, immediateExecutor, 3L);
        DateRange date = new DateRange(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3),
                LocalTime.of(10, 0), LocalTime.of(20, 0));
        request = new RecommendRequest(
                date, 100_000, List.of("역사"), Mobility.WALK, "서울특별시", "동작구");
    }

    @Test
    void missCallsAgentAndLinksJob() {
        String hash = cacheKeyBuilder.hash(request);
        when(reuseCacheStore.find(hash)).thenReturn(Optional.empty());
        JobAccepted agentResult = new JobAccepted("agent-job-1", "in_progress", 3);
        when(agentClient.requestRecommend(request)).thenReturn(agentResult);

        JobAccepted result = service.createRecommendation(request);

        assertThat(result).isEqualTo(agentResult);
        verify(reuseCacheStore).linkJob("agent-job-1", hash);
        verify(draftStore, never()).save(anyString(), anyString());
    }

    @Test
    void hitBelowThresholdReturnsImmediatelyWithoutAgentCall() {
        String hash = cacheKeyBuilder.hash(request);
        when(reuseCacheStore.find(hash)).thenReturn(Optional.of("{\"places\":[]}"));
        when(reuseCacheStore.incrementHits(hash)).thenReturn(1L);

        JobAccepted result = service.createRecommendation(request);

        assertThat(result.status()).isEqualTo("in_progress");
        assertThat(result.jobId()).isNotBlank();
        verify(draftStore).save(eq(result.jobId()), eq("{\"places\":[]}"));
        verify(agentClient, never()).requestRecommend(any());
    }

    @Test
    void hitAtThresholdStillReturnsImmediatelyAndTriggersBackgroundRefresh() {
        String hash = cacheKeyBuilder.hash(request);
        when(reuseCacheStore.find(hash)).thenReturn(Optional.of("{\"places\":[]}"));
        when(reuseCacheStore.incrementHits(hash)).thenReturn(3L);
        JobAccepted backgroundJob = new JobAccepted("bg-job-1", "in_progress", 3);
        when(agentClient.requestRecommend(request)).thenReturn(backgroundJob);

        JobAccepted result = service.createRecommendation(request);

        assertThat(result.jobId()).isNotEqualTo("bg-job-1");
        verify(draftStore).save(eq(result.jobId()), eq("{\"places\":[]}"));
        verify(agentClient, times(1)).requestRecommend(request);
        verify(reuseCacheStore).linkJob("bg-job-1", hash);
    }

    @Test
    void cacheLookupFailureFallsBackToMiss() {
        String hash = cacheKeyBuilder.hash(request);
        when(reuseCacheStore.find(hash)).thenThrow(new RuntimeException("redis down"));
        JobAccepted agentResult = new JobAccepted("agent-job-2", "in_progress", 3);
        when(agentClient.requestRecommend(request)).thenReturn(agentResult);

        JobAccepted result = service.createRecommendation(request);

        assertThat(result).isEqualTo(agentResult);
    }

    @Test
    void backgroundRefreshFailureDoesNotAffectResponse() {
        String hash = cacheKeyBuilder.hash(request);
        when(reuseCacheStore.find(hash)).thenReturn(Optional.of("{\"places\":[]}"));
        when(reuseCacheStore.incrementHits(hash)).thenReturn(3L);
        when(agentClient.requestRecommend(request))
                .thenThrow(new RuntimeException("agent down"));

        JobAccepted result = service.createRecommendation(request);

        assertThat(result.status()).isEqualTo("in_progress");
        assertThat(result.jobId()).isNotBlank();
    }
}

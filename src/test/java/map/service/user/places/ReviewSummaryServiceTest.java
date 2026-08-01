package map.service.user.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import map.service.user.places.dto.ReviewItem;
import map.service.user.places.dto.ReviewSearchResponse;
import map.service.user.places.dto.ReviewSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * ReviewSummaryServiceTest — 장소 요약 조립·캐시 규칙 단위 테스트
 *
 * 캐시가 있으면 모델을 부르지 않고, 실패한 요약은 담지 않으며, 캐시 장애가
 * 응답을 막지 않는지 확인한다.
 */
@DisplayName("ReviewSummaryService 요약 조립과 캐시")
class ReviewSummaryServiceTest {

    /** 캐시에 담긴 두 줄을 잇는 구분자. 서비스와 같은 문자를 쓴다. */
    private static final String JOIN = "\u001F";

    private ReviewSearchClient reviewClient;
    private AgentSummaryClient summaryClient;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ReviewSummaryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        reviewClient = mock(ReviewSearchClient.class);
        summaryClient = mock(AgentSummaryClient.class);
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new ReviewSummaryService(
                reviewClient, summaryClient, redis, 86400);
    }

    private void sourcesAre(ReviewItem... items) {
        when(reviewClient.search(anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(new ReviewSearchResponse(
                        "속초해변", List.of(items), items.length, 1));
    }

    private static ReviewItem item(String title) {
        return new ReviewItem(
                title, "본문 " + title, "블로거", "20260101", "http://x/" + title);
    }

    @Test
    @DisplayName("캐시에 있으면 조회도 요약도 하지 않는다")
    void cacheHitSkipsUpstream() {
        when(ops.get(anyString())).thenReturn("첫 줄" + JOIN + "둘째 줄");

        ReviewSummaryResponse out = service.summarize("속초해변");

        assertThat(out.bullets()).containsExactly("첫 줄", "둘째 줄");
        verify(reviewClient, never())
                .search(anyString(), any(), any(), any());
        verify(summaryClient, never()).summarize(anyString(), any());
    }

    @Test
    @DisplayName("캐시가 없으면 조회 후 요약하고 결과를 담는다")
    void cacheMissFetchesAndStores() {
        when(ops.get(anyString())).thenReturn(null);
        sourcesAre(item("a"), item("b"));
        when(summaryClient.summarize(anyString(), any()))
                .thenReturn(List.of("첫 줄", "둘째 줄"));

        ReviewSummaryResponse out = service.summarize("속초해변");

        assertThat(out.bullets()).containsExactly("첫 줄", "둘째 줄");
        assertThat(out.sourceCount()).isEqualTo(2);
        verify(ops).set(anyString(), any(String.class), any(Duration.class));
    }

    @Test
    @DisplayName("요약에 실패하면 담지 않는다 — 하루 동안 굳지 않도록")
    void failedSummaryIsNotCached() {
        when(ops.get(anyString())).thenReturn(null);
        sourcesAre(item("a"));
        when(summaryClient.summarize(anyString(), any()))
                .thenReturn(List.of());

        ReviewSummaryResponse out = service.summarize("속초해변");

        assertThat(out.bullets()).isEmpty();
        verify(ops, never())
                .set(anyString(), any(String.class), any(Duration.class));
    }

    @Test
    @DisplayName("근거가 없으면 모델을 부르지 않는다")
    void noSourcesSkipsModel() {
        when(ops.get(anyString())).thenReturn(null);
        when(reviewClient.search(anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(new ReviewSearchResponse(
                        "속초해변", List.of(), 0, 1));

        ReviewSummaryResponse out = service.summarize("속초해변");

        assertThat(out.bullets()).isEmpty();
        assertThat(out.sourceCount()).isZero();
        verify(summaryClient, never()).summarize(anyString(), any());
    }

    @Test
    @DisplayName("블로그 조회가 실패해도 오류로 올리지 않는다")
    void upstreamFailureDegrades() {
        when(ops.get(anyString())).thenReturn(null);
        when(reviewClient.search(anyString(), anyInt(), anyInt(), anyString()))
                .thenThrow(new IllegalStateException("boom"));

        ReviewSummaryResponse out = service.summarize("속초해변");

        assertThat(out.bullets()).isEmpty();
    }

    @Test
    @DisplayName("캐시 장애가 응답을 막지 않는다")
    void cacheFailureDoesNotBlock() {
        when(ops.get(anyString()))
                .thenThrow(new IllegalStateException("redis down"));
        sourcesAre(item("a"));
        when(summaryClient.summarize(anyString(), any()))
                .thenReturn(List.of("첫 줄", "둘째 줄"));

        ReviewSummaryResponse out = service.summarize("속초해변");

        assertThat(out.bullets()).containsExactly("첫 줄", "둘째 줄");
    }

    @Test
    @DisplayName("같은 장소를 다르게 적어도 한 키로 모인다")
    void keyNormalizesQuery() {
        when(ops.get(anyString())).thenReturn("첫 줄" + JOIN + "둘째 줄");

        service.summarize("  속초해변  ");
        service.summarize("속초해변");

        // 두 호출이 같은 키를 읽었다면 캐시 히트가 두 번 일어난다.
        verify(reviewClient, never())
                .search(anyString(), any(), any(), any());
    }
}

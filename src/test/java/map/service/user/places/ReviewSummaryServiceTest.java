package map.service.user.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        // 선작성은 제출한 자리에서 바로 돌린다 — 별도 스레드로 넘기면 단언이
        // 작업 완료 전에 실행돼 결과가 들쭉날쭉해진다.
        service = new ReviewSummaryService(
                reviewClient, summaryClient, redis, 86400,
                Runnable::run, true, 7, 7);
    }

    /** 선작성을 끈 서비스. 스위치를 내렸을 때의 동작을 본다. */
    private ReviewSummaryService serviceWithPrewarmDisabled() {
        return new ReviewSummaryService(
                reviewClient, summaryClient, redis, 86400,
                Runnable::run, false, 7, 7);
    }

    /** 한 번에 묶어 보낼 장소 수를 좁힌 서비스. 분할 동작을 본다. */
    private ReviewSummaryService serviceWithBatchSize(int size) {
        return new ReviewSummaryService(
                reviewClient, summaryClient, redis, 86400,
                Runnable::run, true, 7, size);
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
        when(ops.get(anyString())).thenReturn("2" + JOIN + "첫 줄" + JOIN + "둘째 줄");

        ReviewSummaryResponse out = service.summarize("속초해변");

        assertThat(out.bullets()).containsExactly("첫 줄", "둘째 줄");
        // 근거 글 수도 함께 담겨 있어 캐시 히트가 미스와 같은 값을 준다.
        assertThat(out.sourceCount()).isEqualTo(2);
        verify(reviewClient, never())
                .search(anyString(), any(), any(), any());
        verify(summaryClient, never()).summarize(anyString(), any());
    }

    @Test
    @DisplayName("옛 형식으로 담긴 캐시는 미스로 다뤄 다시 만든다")
    void legacyCacheShapeIsTreatedAsMiss() {
        when(ops.get(anyString())).thenReturn("첫 줄" + JOIN + "둘째 줄");
        sourcesAre(item("a"), item("b"), item("c"));
        when(summaryClient.summarize(anyString(), any()))
                .thenReturn(List.of("새 첫 줄", "새 둘째 줄"));

        ReviewSummaryResponse out = service.summarize("속초해변");

        assertThat(out.bullets()).containsExactly("새 첫 줄", "새 둘째 줄");
        assertThat(out.sourceCount()).isEqualTo(3);
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
        when(ops.get(anyString())).thenReturn("2" + JOIN + "첫 줄" + JOIN + "둘째 줄");

        service.summarize("  속초해변  ");
        service.summarize("속초해변");

        // 두 호출이 같은 키를 읽었다면 캐시 히트가 두 번 일어난다.
        verify(reviewClient, never())
                .search(anyString(), any(), any(), any());
    }

    // ── 선작성(prewarm) ───────────────────────────────────────

    /** 선작성에 넘길 장소 한 건. */
    private static ReviewSummaryService.PrewarmPlace place(String name) {
        return new ReviewSummaryService.PrewarmPlace(name, "해변");
    }

    /** 배치 요약 대역 — 보낸 순서대로 두 줄씩 돌려준다. */
    private void batchAnswersInOrder() {
        when(summaryClient.summarizeBatch(any())).thenAnswer(inv -> {
            List<AgentSummaryClient.BatchPlace> sent = inv.getArgument(0);
            Map<Integer, List<String>> out = new LinkedHashMap<>();
            for (int i = 0; i < sent.size(); i++) {
                out.put(i, List.of(sent.get(i).name() + "-1",
                        sent.get(i).name() + "-2"));
            }
            return out;
        });
    }

    @Test
    @DisplayName("선작성이 장소마다 캐시에 담는다")
    void prewarmWritesOneEntryPerPlace() {
        when(ops.get(anyString())).thenReturn(null);
        sourcesAre(item("a"), item("b"));
        batchAnswersInOrder();

        service.prewarm(List.of(place("속초해변"), place("영금정")));

        verify(ops).set(anyString(), eq("2" + JOIN + "속초해변-1" + JOIN + "속초해변-2"),
                any(Duration.class));
        verify(ops).set(anyString(), eq("2" + JOIN + "영금정-1" + JOIN + "영금정-2"),
                any(Duration.class));
    }

    @Test
    @DisplayName("이미 담긴 장소는 다시 만들지 않는다")
    void prewarmSkipsCachedPlaces() {
        when(ops.get(anyString()))
                .thenReturn("2" + JOIN + "첫 줄" + JOIN + "둘째 줄");

        service.prewarm(List.of(place("속초해변")));

        verify(reviewClient, never())
                .search(anyString(), any(), any(), any());
        verify(summaryClient, never()).summarizeBatch(any());
    }

    @Test
    @DisplayName("같은 이름이 겹치면 한 번만 만든다")
    void prewarmDedupesRepeatedNames() {
        when(ops.get(anyString())).thenReturn(null);
        sourcesAre(item("a"));
        batchAnswersInOrder();

        service.prewarm(List.of(place("속초해변"), place("  속초해변  ")));

        verify(reviewClient).search(anyString(), anyInt(), anyInt(), anyString());
    }

    @Test
    @DisplayName("근거를 못 구한 장소는 건너뛴다")
    void prewarmSkipsPlacesWithoutSources() {
        when(ops.get(anyString())).thenReturn(null);
        when(reviewClient.search(anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(new ReviewSearchResponse("속초해변", List.of(), 0, 1));

        service.prewarm(List.of(place("속초해변")));

        verify(summaryClient, never()).summarizeBatch(any());
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("빈 요약은 담지 않는다")
    void prewarmDoesNotCacheEmptyBullets() {
        when(ops.get(anyString())).thenReturn(null);
        sourcesAre(item("a"));
        when(summaryClient.summarizeBatch(any())).thenReturn(Map.of());

        service.prewarm(List.of(place("속초해변")));

        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("요약 호출이 실패해도 밖으로 새지 않는다")
    void prewarmSwallowsFailure() {
        when(ops.get(anyString())).thenReturn(null);
        sourcesAre(item("a"));
        when(summaryClient.summarizeBatch(any()))
                .thenThrow(new IllegalStateException("boom"));

        service.prewarm(List.of(place("속초해변")));

        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("스위치를 내리면 아무 것도 하지 않는다")
    void prewarmDisabledDoesNothing() {
        serviceWithPrewarmDisabled().prewarm(List.of(place("속초해변")));

        verify(ops, never()).get(anyString());
        verify(summaryClient, never()).summarizeBatch(any());
    }

    @Test
    @DisplayName("묶음 상한을 넘으면 나눠 부른다")
    void prewarmChunksBeyondBatchSize() {
        when(ops.get(anyString())).thenReturn(null);
        sourcesAre(item("a"));
        batchAnswersInOrder();

        serviceWithBatchSize(2).prewarm(
                List.of(place("가"), place("나"), place("다")));

        verify(summaryClient, times(2)).summarizeBatch(any());
    }

    @Test
    @DisplayName("근거 없는 장소가 중간에 끼어도 요약이 밀리지 않는다")
    void prewarmKeepsNameAlignmentWhenMiddlePlaceHasNoSources() {
        when(ops.get(anyString())).thenReturn(null);
        // 가운데 장소만 근거가 없다 — 보낸 목록에서 빠지므로 응답 위치가
        // 원래 위치와 어긋난다. 되짚기를 놓치면 '다' 의 요약이 '나' 에 붙는다.
        when(reviewClient.search(eq("가"), anyInt(), anyInt(), anyString()))
                .thenReturn(new ReviewSearchResponse(
                        "가", List.of(item("a")), 1, 1));
        when(reviewClient.search(eq("나"), anyInt(), anyInt(), anyString()))
                .thenReturn(new ReviewSearchResponse("나", List.of(), 0, 1));
        when(reviewClient.search(eq("다"), anyInt(), anyInt(), anyString()))
                .thenReturn(new ReviewSearchResponse(
                        "다", List.of(item("c")), 1, 1));
        batchAnswersInOrder();

        service.prewarm(List.of(place("가"), place("나"), place("다")));

        // 담긴 값이 각자의 이름을 근거로 만들어졌는지 본다.
        verify(ops).set(anyString(), eq("1" + JOIN + "가-1" + JOIN + "가-2"),
                any(Duration.class));
        verify(ops).set(anyString(), eq("1" + JOIN + "다-1" + JOIN + "다-2"),
                any(Duration.class));
        verify(ops, times(2)).set(anyString(), anyString(), any(Duration.class));
    }
}

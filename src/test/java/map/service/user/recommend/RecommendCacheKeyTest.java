package map.service.user.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import map.service.user.recommend.dto.DateRange;
import map.service.user.recommend.dto.Mobility;
import map.service.user.recommend.dto.RecommendRequest;
import org.junit.jupiter.api.Test;

class RecommendCacheKeyTest {

    private final RecommendCacheKey cacheKey = new RecommendCacheKey(50_000, 60);

    private RecommendRequest request(List<String> theme, Integer budget, Mobility mobility) {
        return request(theme, budget, mobility, LocalTime.of(10, 0), LocalTime.of(20, 0));
    }

    private RecommendRequest request(
            List<String> theme, Integer budget, Mobility mobility,
            LocalTime timeStart, LocalTime timeEnd) {
        DateRange date = new DateRange(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3),
                timeStart, timeEnd);
        return new RecommendRequest(date, budget, theme, mobility, "서울특별시", "동작구");
    }

    @Test
    void sameFieldsProduceSameHash() {
        RecommendRequest a = request(List.of("역사", "맛집"), 100_000, Mobility.WALK);
        RecommendRequest b = request(List.of("역사", "맛집"), 100_000, Mobility.WALK);

        assertThat(cacheKey.hash(a)).isEqualTo(cacheKey.hash(b));
    }

    @Test
    void themeOrderIsIgnored() {
        RecommendRequest a = request(List.of("역사", "맛집"), 100_000, Mobility.WALK);
        RecommendRequest b = request(List.of("맛집", "역사"), 100_000, Mobility.WALK);

        assertThat(cacheKey.hash(a)).isEqualTo(cacheKey.hash(b));
    }

    @Test
    void differentProvinceProducesDifferentHash() {
        RecommendRequest seoul = request(List.of("역사"), 100_000, Mobility.WALK);
        RecommendRequest busan = new RecommendRequest(
                seoul.date(), seoul.budget(), seoul.theme(), seoul.mobility(),
                "부산광역시", seoul.city());

        assertThat(cacheKey.hash(seoul)).isNotEqualTo(cacheKey.hash(busan));
    }

    @Test
    void budgetWithinRoundingStepProducesSameHash() {
        RecommendRequest a = request(List.of("역사"), 124_999, Mobility.WALK);
        RecommendRequest b = request(List.of("역사"), 100_000, Mobility.WALK);

        assertThat(cacheKey.hash(a)).isEqualTo(cacheKey.hash(b));
    }

    @Test
    void budgetAcrossRoundingBoundaryProducesDifferentHash() {
        RecommendRequest a = request(List.of("역사"), 149_999, Mobility.WALK);
        RecommendRequest b = request(List.of("역사"), 150_000, Mobility.WALK);

        assertThat(cacheKey.hash(a)).isNotEqualTo(cacheKey.hash(b));
    }

    @Test
    void timeWithinRoundingStepProducesSameHash() {
        RecommendRequest a = request(
                List.of("역사"), 100_000, Mobility.WALK, LocalTime.of(10, 29), LocalTime.of(20, 0));
        RecommendRequest b = request(
                List.of("역사"), 100_000, Mobility.WALK, LocalTime.of(10, 0), LocalTime.of(20, 0));

        assertThat(cacheKey.hash(a)).isEqualTo(cacheKey.hash(b));
    }

    @Test
    void timeAcrossRoundingBoundaryProducesDifferentHash() {
        RecommendRequest a = request(
                List.of("역사"), 100_000, Mobility.WALK, LocalTime.of(10, 29), LocalTime.of(20, 0));
        RecommendRequest b = request(
                List.of("역사"), 100_000, Mobility.WALK, LocalTime.of(10, 30), LocalTime.of(20, 0));

        assertThat(cacheKey.hash(a)).isNotEqualTo(cacheKey.hash(b));
    }

    @Test
    void nullBudgetAndNullMobilityAreStableSentinels() {
        RecommendRequest a = request(List.of("역사"), null, null);
        RecommendRequest b = request(List.of("역사"), null, null);

        assertThat(cacheKey.hash(a)).isEqualTo(cacheKey.hash(b));
    }

    @Test
    void differentDateProducesDifferentHash() {
        RecommendRequest a = request(List.of("역사"), 100_000, Mobility.WALK);
        DateRange otherDate = new DateRange(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3),
                LocalTime.of(10, 0), LocalTime.of(20, 0));
        RecommendRequest b = new RecommendRequest(
                otherDate, a.budget(), a.theme(), a.mobility(), a.province(), a.city());

        assertThat(cacheKey.hash(a)).isNotEqualTo(cacheKey.hash(b));
    }
}

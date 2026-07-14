package map.service.user.admin;

import map.service.user.admin.dto.JobStats;
import map.service.user.recommend.RecommendJobRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AdminJobQueryServiceTest — 추천 작업 통계 단위 테스트 (Mockito)
 *
 * countGroupByStatus 집계 결과를 byStatus/total 로 접고, 최근 24h 실패수를
 * 결합하는지 검증한다.
 */
@DisplayName("AdminJobQueryService 단위 테스트")
class AdminJobQueryServiceTest {

    private final RecommendJobRepository repository = mock(RecommendJobRepository.class);
    private final AdminJobQueryService service = new AdminJobQueryService(repository);

    @Test
    @DisplayName("stats — 상태별 집계 + 총계 + 최근 24h 실패수")
    void stats_aggregates() {
        when(repository.countGroupByStatus()).thenReturn(List.of(
                new Object[]{"done", 5L},
                new Object[]{"failed", 2L},
                new Object[]{"in_progress", 1L}));
        when(repository.countByStatusAndCreatedAtAfter(eq("failed"), any(OffsetDateTime.class)))
                .thenReturn(1L);

        JobStats stats = service.stats();

        assertThat(stats.byStatus()).containsEntry("done", 5L)
                .containsEntry("failed", 2L).containsEntry("in_progress", 1L);
        assertThat(stats.total()).isEqualTo(8L);
        assertThat(stats.failedLast24h()).isEqualTo(1L);
    }
}

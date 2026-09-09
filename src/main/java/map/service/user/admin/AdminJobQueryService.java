package map.service.user.admin;

import map.service.user.admin.dto.AdminJobSummary;
import map.service.user.admin.dto.JobStats;
import map.service.user.admin.dto.PageResponse;
import map.service.user.recommend.RecommendJobEntity;
import map.service.user.recommend.RecommendJobRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AdminJobQueryService — 운영 콘솔 위임 추천 작업 조회
 *
 * map-service-admin 이 /internal/admin/recommend-jobs* 로 호출한다. 통계(상태별
 * 건수 + 최근 24h 실패수)와 목록(대용량 payload 제외)을 제공한다.
 */
@Service
public class AdminJobQueryService {

    private static final int MAX_SIZE = 100;

    private final RecommendJobRepository repository;

    public AdminJobQueryService(RecommendJobRepository repository) {
        this.repository = repository;
    }

    /** 상태별 건수 + 총계 + 최근 24시간 실패 건수. */
    @Transactional(readOnly = true)
    public JobStats stats() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long total = 0;
        for (Object[] row : repository.countGroupByStatus()) {
            String status = (String) row[0];
            long cnt = ((Number) row[1]).longValue();
            byStatus.put(status, cnt);
            total += cnt;
        }
        long failed24h = repository.countByStatusAndCreatedAtAfter(
                "failed", OffsetDateTime.now().minusHours(24));
        return new JobStats(byStatus, total, failed24h);
    }

    /**
     * 추천 작업 목록. status 가 비면 전체, 있으면 해당 상태만. 최신순 정렬.
     * result_payload 는 목록에 싣지 않는다.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminJobSummary> list(String status, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                clampSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<RecommendJobEntity> result = (status == null || status.isBlank())
                ? repository.findAll(pageable)
                : repository.findByStatus(status.trim(), pageable);

        List<AdminJobSummary> items = result.getContent().stream()
                .map(e -> new AdminJobSummary(
                        e.getJobId() == null ? null : e.getJobId().toString(),
                        e.getScheduleId(),
                        e.getStatus(),
                        e.getError() == null || e.getError().isBlank() ? null : "job_failed",
                        e.getCreatedAt(),
                        e.getFinishedAt()))
                .toList();
        return new PageResponse<>(
                items,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    private static int clampSize(int size) {
        if (size < 1) {
            return 20;
        }
        return Math.min(size, MAX_SIZE);
    }
}

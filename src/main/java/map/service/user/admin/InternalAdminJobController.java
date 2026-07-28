package map.service.user.admin;

import map.service.user.admin.dto.AdminJobSummary;
import map.service.user.admin.dto.JobStats;
import map.service.user.admin.dto.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * InternalAdminJobController — 운영 콘솔 위임 추천 작업 조회 API
 *
 * InternalAdminGuardFilter 뒤에서만 접근 가능.
 *
 *   GET /internal/admin/recommend-jobs/stats          — 상태별 통계
 *   GET /internal/admin/recommend-jobs?status=&page=  — 작업 목록(payload 제외)
 */
@RestController
@RequestMapping("/internal/admin/recommend-jobs")
public class InternalAdminJobController {

    private final AdminJobQueryService service;

    public InternalAdminJobController(AdminJobQueryService service) {
        this.service = service;
    }

    /** 상태별 건수 + 총계 + 최근 24h 실패수. */
    @GetMapping("/stats")
    public JobStats stats() {
        return service.stats();
    }

    /** 작업 목록. status 미지정 시 전체, 최신순. */
    @GetMapping
    public PageResponse<AdminJobSummary> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(status, page, size);
    }
}

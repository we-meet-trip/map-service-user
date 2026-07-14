package map.service.user.admin;

import map.service.user.admin.dto.AdminUserDetail;
import map.service.user.admin.dto.AdminUserSummary;
import map.service.user.admin.dto.PageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * InternalAdminUserController — 운영 콘솔 위임 회원 조회 API
 *
 * 경로 /internal/admin/users 는 InternalAdminGuardFilter(CIDR + X-Internal-Token)
 * 뒤에서만 접근 가능하다(도메인 인증 체인과 분리). map-service-admin 이 유일 소비자.
 *
 *   GET /internal/admin/users?query=&page=&size=  — 회원 목록(이메일 마스킹)
 *   GET /internal/admin/users/{id}                — 회원 상세(원문 이메일)
 */
@RestController
@RequestMapping("/internal/admin/users")
public class InternalAdminUserController {

    private final AdminUserQueryService service;

    public InternalAdminUserController(AdminUserQueryService service) {
        this.service = service;
    }

    /** 회원 목록 검색(마스킹). query 미지정 시 전체, 최신 가입순. */
    @GetMapping
    public PageResponse<AdminUserSummary> list(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.search(query, page, size);
    }

    /** 회원 상세(원문 이메일 포함). 없으면 404. */
    @GetMapping("/{id}")
    public ResponseEntity<AdminUserDetail> detail(@PathVariable Long id) {
        return service.detail(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

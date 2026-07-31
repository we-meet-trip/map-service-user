package map.service.user.schedule;

import jakarta.validation.Valid;
import java.util.Map;
import map.service.user.schedule.dto.ScheduleDetailResponse;
import map.service.user.schedule.dto.ScheduleListResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ScheduleController — 일정 도메인 HTTP 진입점
 *
 * /api/v1/schedules 경로의 일정 저장 엔드포인트를 노출한다.
 * 모든 비즈니스 동작은 ScheduleService 로 위임한다.
 *
 * service: ScheduleService. 생성자 주입.
 *
 * 엔드포인트:
 * - POST   /api/v1/schedules       → save   (draft 를 일정으로 저장)
 * - GET    /api/v1/schedules       → list   (내 일정 목록)
 * - GET    /api/v1/schedules/{id}  → detail (방문지까지 조립된 상세)
 * - DELETE /api/v1/schedules/{id}  → delete
 *
 * 조회·삭제는 소유자 범위로 제한된다. 남의 일정이나 없는 일정은 똑같이
 * 404 다 — 403 과 구분하면 그 일정이 존재한다는 사실이 새어 나간다.
 */
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleController {

    private final ScheduleService service;

    public ScheduleController(ScheduleService service) {
        this.service = service;
    }

    /**
     * draft 를 일정으로 영속화.
     *
     * 클라이언트가 jobId/title/날짜를 본문으로 보내면 ScheduleService.persist 에 위임하여
     * draft 를 일정 엔티티로 저장하고 발급된 schedule_id 를 JSON 으로 돌려준다.
     * draft 가 없으면 서비스 계층에서 ScheduleNotFoundException(404) 으로 처리된다.
     *
     * request: @Valid @RequestBody ScheduleSaveRequest.
     * userId: @AuthenticationPrincipal 로 주입되는 소유자 식별자. JWT 필터가 채운
     *         SecurityContext 의 principal(Long). 토큰이 없거나 익명(anonymousUser
     *         String)인 경우 리졸버가 null 로 해석하여 소유자 미지정으로 저장한다
     *         (auth.enforced=false + 토큰 부재 시 현행 동작과 동일).
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> save(
            @Valid @RequestBody ScheduleSaveRequest request,
            @AuthenticationPrincipal Long userId
    ) {
        Long scheduleId = service.persist(request, userId);
        return ResponseEntity.ok(Map.of("schedule_id", scheduleId));
    }

    /**
     * 내 일정 목록 조회.
     *
     * 저장 때와 같은 소유자 판정을 쓴다 — 토큰이 있으면 그 사용자의 일정,
     * 없으면 소유자 미지정으로 저장된 일정만 돌려준다.
     */
    @GetMapping
    public ResponseEntity<ScheduleListResponse> list(
            @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(service.list(userId));
    }

    /**
     * 일정 상세 조회. 방문지·이동 카드·도로 경로까지 조립해 돌려준다.
     *
     * 소유자가 아니거나 없는 일정이면 ScheduleNotFoundException(404).
     */
    @GetMapping("/{scheduleId}")
    public ResponseEntity<ScheduleDetailResponse> detail(
            @PathVariable Long scheduleId,
            @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(service.detail(scheduleId, userId));
    }

    /**
     * 일정 삭제. 성공 시 본문 없이 204.
     */
    @DeleteMapping("/{scheduleId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long scheduleId,
            @AuthenticationPrincipal Long userId
    ) {
        service.delete(scheduleId, userId);
        return ResponseEntity.noContent().build();
    }
}

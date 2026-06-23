package map.service.user.schedule;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
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
 * - POST /api/v1/schedules → save
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
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> save(
            @Valid @RequestBody ScheduleSaveRequest request
    ) {
        Long scheduleId = service.persist(request);
        return ResponseEntity.ok(Map.of("schedule_id", scheduleId));
    }
}

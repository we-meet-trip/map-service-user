package map.service.user.schedule;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.security.JwtAuthenticationFilter;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.schedule.dto.ArrivalRequest;
import map.service.user.schedule.dto.ScheduleDetailResponse;
import map.service.user.schedule.dto.ScheduleListResponse;
import map.service.user.schedule.dto.ScheduleReviseRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
 * - POST   /api/v1/schedules             → save   (draft 를 일정으로 저장)
 * - GET    /api/v1/schedules             → list   (내 일정 목록)
 * - GET    /api/v1/schedules/{id}        → detail (방문지까지 조립된 상세)
 * - POST   /api/v1/schedules/{id}/start  → start  (시작 기록 + 상세)
 * - POST   /api/v1/schedules/{id}/replan → replan (날씨 변화 뒤 1클릭 재추천)
 * - POST   /api/v1/schedules/{id}/weather-alert/dismiss → 알림 무시(이대로 유지)
 * - DELETE /api/v1/schedules/{id}        → delete
 *
 * 조회·삭제는 소유자 범위로 제한된다. 남의 일정이나 없는 일정은 똑같이
 * 404 다 — 403 과 구분하면 그 일정이 존재한다는 사실이 새어 나간다.
 *
 * 저장도 같은 범위를 따른다. 조회가 소유자로 좁혀지는 이상 주인 없는 저장은
 * 다시 꺼낼 수 없는 행만 남기므로, 소유자를 특정하지 못하면 401 로 되돌린다.
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
     * <p><b>소유자가 없으면 저장하지 않는다.</b> 목록·상세·삭제가 전부 소유자 조건으로
     * 조회하므로, 주인 없이 저장된 행은 어떤 경로로도 다시 꺼낼 수 없다. 그런데도 200 을
     * 돌려주면 클라이언트는 저장에 성공했다고 알리고, 사용자는 목록에서 그 일정을 영영
     * 찾지 못한다. 저장됐다는 응답은 "다시 꺼낼 수 있다"는 뜻이어야 하므로 401 로 되돌린다.
     *
     * <p>토큰을 들고 왔는데 만료돼서 거절된 경우에는 그 사유를 그대로 실어 준다.
     * 클라이언트는 401 을 받으면 토큰을 갱신해 한 번 다시 시도하는데, 사유가 정확해야
     * 그 경로가 제대로 걸린다.
     *
     * request: @Valid @RequestBody ScheduleSaveRequest.
     * userId: @AuthenticationPrincipal 로 주입되는 소유자 식별자. JWT 필터가 채운
     *         SecurityContext 의 principal(Long). 토큰이 없거나 검증에 실패하면 null.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> save(
            @Valid @RequestBody ScheduleSaveRequest request,
            @AuthenticationPrincipal Long userId,
            HttpServletRequest httpRequest
    ) {
        if (userId == null) {
            Object rejected =
                    httpRequest.getAttribute(JwtAuthenticationFilter.REJECTED_TOKEN_ATTR);
            throw new CustomException(rejected instanceof ErrorCode code
                    ? code
                    : ErrorCode.INVALID_TOKEN);
        }
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
     * 저장된 일정의 방문지를 새로 만든 동선으로 갈아 끼운다.
     *
     * 화면에서 장소를 더하거나 빼거나 순서를 바꾼 뒤 그 목록으로 동선을
     * 새로 만들고(POST /api/v1/trip/route), 그 결과의 작업 식별자를 여기로
     * 보낸다. 방문 순서·이동 구간·시각이 서로 맞물려 있어 방문지를 하나씩
     * 고치는 통로는 두지 않는다.
     *
     * 소유자가 아니거나 없는 일정이면 404. 보낸 작업의 초안이 이미 사라졌어도
     * 404 다 — 만든 직후에 부르는 요청이라 정상 흐름에서는 남아 있다.
     *
     * request: @Valid @RequestBody ScheduleReviseRequest.
     */
    @PutMapping("/{scheduleId}")
    public ResponseEntity<ScheduleDetailResponse> revise(
            @PathVariable Long scheduleId,
            @Valid @RequestBody ScheduleReviseRequest request,
            @AuthenticationPrincipal Long userId,
            HttpServletRequest httpRequest
    ) {
        if (userId == null) {
            Object rejected =
                    httpRequest.getAttribute(JwtAuthenticationFilter.REJECTED_TOKEN_ATTR);
            throw new CustomException(rejected instanceof ErrorCode code
                    ? code
                    : ErrorCode.INVALID_TOKEN);
        }
        return ResponseEntity.ok(service.revise(scheduleId, userId, request));
    }

    /**
     * 일정을 따라가기 시작했다고 알리고, 시작 화면이 그릴 상세를 받는다.
     *
     * 시작 시각은 처음 한 번만 새겨지므로 몇 번을 눌러도 결과가 같다.
     * 소유자가 아니거나 없는 일정이면 조회와 똑같이 404 다.
     */
    @PostMapping("/{scheduleId}/start")
    public ResponseEntity<ScheduleDetailResponse> start(
            @PathVariable Long scheduleId,
            @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(service.start(scheduleId, userId));
    }

    /**
     * 날씨가 바뀐 일정을 저장된 조건 그대로 다시 추천한다(배너의 1클릭 재추천).
     *
     * 저장해 둔 지역·기간·이동수단으로 새 추천 작업을 띄우고 202 + job_id 를
     * 준다. 결과는 기존 GET /api/v1/recommend/{jobId} 로 받으므로 클라이언트가
     * 조회 코드를 새로 만들 필요가 없다.
     *
     * 재탐색(mode1)이 아니라 일반 추천 경로라 1일 3회 한도를 깎지 않는다.
     * 지역을 모르는 옛 일정이면 409(ScheduleReplanUnavailableException).
     * 소유자가 아니거나 없는 일정이면 404.
     */
    @PostMapping("/{scheduleId}/replan")
    public ResponseEntity<JobAccepted> replan(
            @PathVariable Long scheduleId,
            @AuthenticationPrincipal Long userId
    ) {
        if (userId == null) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }
        return ResponseEntity.accepted().body(service.replan(scheduleId, userId));
    }

    /**
     * 날씨 알림을 받아들이지 않고 지운다("이대로 갈래"). 성공 시 본문 없이 204.
     *
     * 알림만 지우면 다음 감시 순회에서 같은 변화가 다시 잡혀 또 붙는다. 그래서
     * 서비스가 기준선을 지금 예보로 옮겨, 사용자가 알고도 그대로 가기로 한
     * 지점을 기억한다. 이후 예보가 또 달라지면 새 기준선 대비로 다시 알린다.
     *
     * 소유자가 아니거나 없는 일정이면 404.
     */
    @PostMapping("/{scheduleId}/weather-alert/dismiss")
    public ResponseEntity<Void> dismissWeatherAlert(
            @PathVariable Long scheduleId,
            @AuthenticationPrincipal Long userId
    ) {
        if (userId == null) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }
        service.dismissWeatherAlert(scheduleId, userId);
        return ResponseEntity.noContent().build();
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

    /**
     * 방문지 도착 알림. 처음 닿은 것만 남고, 같은 자리를 다시 알려도 200 이다.
     *
     * 같은 자리를 여러 번 알려 오는 것이 정상이라 성공/중복을 나눠 응답하지
     * 않는다 — 기기는 위치가 들어올 때마다 판정하고, 통신이 끊겼다 이어지면
     * 다시 보낸다. 부르는 쪽이 실패를 신경 쓰지 않아도 되게 한다.
     *
     * scheduleId: 저장된 일정. 소유자가 아니면 404.
     * request: @Valid @RequestBody ArrivalRequest.
     */
    @PostMapping("/{scheduleId}/arrivals")
    public ResponseEntity<Void> recordArrival(
            @PathVariable Long scheduleId,
            @Valid @RequestBody ArrivalRequest request,
            @AuthenticationPrincipal Long userId
    ) {
        service.recordArrival(scheduleId, userId,
                request.day(), request.stopOrder(), request.arrivedAt());
        return ResponseEntity.ok().build();
    }
}

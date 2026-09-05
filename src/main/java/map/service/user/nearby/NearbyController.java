package map.service.user.nearby;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 저장한 일정의 방문지 주변 장소.
 *
 * <p>- GET  /api/v1/schedules/{id}/nearby       — 그 자리 주변을 찾아 준다
 * <p>- POST /api/v1/schedules/{id}/nearby/click — 그중 하나를 눌렀다고 남긴다
 *
 * <p>둘 다 주인만 부를 수 있다. 남의 일정 주변을 열어 주면 그 사람이 어디를
 * 가려 했는지가 그대로 드러난다.
 */
@RestController
@RequestMapping("/api/v1/schedules/{scheduleId}/nearby")
public class NearbyController {

    private final NearbyService service;

    public NearbyController(NearbyService service) {
        this.service = service;
    }

    /**
     * 방문지 주변 장소를 가까운 것부터 준다.
     *
     * <p>모르는 분류는 400 이다. 빈 목록으로 답하면 부르는 쪽이 "근처에 없다"
     * 로 잘못 읽는다.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> find(
            @PathVariable Long scheduleId,
            @RequestParam @Min(1) @Max(30) int day,
            @RequestParam(name = "stop_order") @Min(1) @Max(100) int stopOrder,
            @RequestParam String category,
            @AuthenticationPrincipal Long userId
    ) {
        if (!NearbyService.isKnownCategory(category)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "category must be one of stay|food|cafe"));
        }
        List<NearbyPlace> places =
                service.find(scheduleId, userId, day, stopOrder, category);
        return ResponseEntity.ok(Map.of("places", places, "count", places.size()));
    }

    /**
     * 주변 장소를 눌렀다고 남긴다.
     *
     * <p>보여 준 적 없는 것을 눌렀다고 해도 200 이다. 부르는 쪽이 실패를
     * 신경 쓰지 않아도 되게 한다 — 이것은 화면이 도는 조건이 아니다.
     */
    @PostMapping("/click")
    public ResponseEntity<Void> click(
            @PathVariable Long scheduleId,
            @Valid @RequestBody ClickRequest request,
            @AuthenticationPrincipal Long userId
    ) {
        service.recordClick(scheduleId, userId, request.day(), request.stopOrder(),
                request.category(), request.contentId());
        return ResponseEntity.ok().build();
    }

    /** 무엇을 눌렀는지. 좌표는 받지 않는다 — 자리는 (일차, 순번) 으로 지목한다. */
    public record ClickRequest(
            @NotNull @Min(1) @Max(30) Integer day,
            @JsonProperty("stop_order") @NotNull @Min(1) @Max(100) Integer stopOrder,
            @NotBlank @Size(max = 16) String category,
            @JsonProperty("content_id") @NotBlank @Size(max = 64) String contentId
    ) {
    }
}

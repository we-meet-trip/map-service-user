package map.service.user.schedule.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

/**
 * "이 방문지에 닿았다" 는 알림.
 *
 * <p>좌표를 받지 않는다. 어디였는지는 일정에 이미 적혀 있고, (일차, 순번) 으로
 * 그 자리를 지목할 수 있다. 판정 자체는 기기에서 끝낸다.
 *
 * <p>arrivedAt 을 기기가 보내는 이유: 알림이 늦게 도착할 수 있다(통신이 끊겼다
 * 이어지는 자리가 실제로 있다). 서버가 받은 시각을 쓰면 그만큼 밀린다.
 * 상한은 두지 않되, 말이 안 되는 값은 아래 서비스가 소유권과 함께 거른다.
 */
public record ArrivalRequest(
        @NotNull @Min(1) @Max(30) Integer day,
        @JsonProperty("stop_order") @NotNull @Min(1) @Max(100) Integer stopOrder,
        @JsonProperty("arrived_at") @NotNull OffsetDateTime arrivedAt
) {
}

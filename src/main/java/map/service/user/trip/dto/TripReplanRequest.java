package map.service.user.trip.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * TripReplanRequest — 저장된 일정의 재추천 요청 본문
 *
 * 조건을 다시 받지 않는 것이 요점이다. 지역·기간·이동수단·활동 시간대는 저장된
 * 일정에 이미 있고, 사용자가 그것을 다시 입력하지 않아도 되게 하려고 만든
 * 기능이라 식별자 하나만 받는다.
 *
 * scheduleId: 다시 짤 일정. 소유자 확인은 토큰으로 한다.
 */
public record TripReplanRequest(
        @JsonProperty("schedule_id") @NotNull Long scheduleId
) {
}

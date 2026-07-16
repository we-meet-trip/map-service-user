package map.service.user.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/**
 * CreateRoomRequest — 채팅방 생성 요청 본문
 *
 * 어떤 저장된 일정에 방을 만들지 지정한다. scheduleId 가 가리키는 일정의 소유자만
 * 방을 개설할 수 있으며, 같은 일정으로 이미 방이 있으면 그 방이 반환된다(생성 또는 조회).
 *
 * scheduleId: 앵커 일정 식별자. JSON key "schedule_id". 값이 없으면 400 으로 거부된다.
 */
public record CreateRoomRequest(
        @JsonProperty("schedule_id")
        @NotNull
        Long scheduleId
) {
}

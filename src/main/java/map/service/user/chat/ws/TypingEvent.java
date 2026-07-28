package map.service.user.chat.ws;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * TypingEvent — 입력 상태 이벤트 payload
 *
 * 어떤 사용자가 입력을 시작했는지(true)/멈췄는지(false)를 알린다. 저장하지 않는 휘발성
 * 이벤트로, 클라이언트는 이 값에 따라 "입력 중" 표시를 켜고 끈다.
 */
public record TypingEvent(
        @JsonProperty("user_id") long userId,
        boolean typing
) {
}

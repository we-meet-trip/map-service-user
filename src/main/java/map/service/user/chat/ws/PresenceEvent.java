package map.service.user.chat.ws;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PresenceEvent — 접속 상태 이벤트 payload
 *
 * 어떤 사용자가 방에 접속했는지(online=true)/떠났는지(false)를 알린다. 클라이언트는 이
 * 값으로 참가자의 온라인 표시를 갱신한다. 같은 사용자가 여러 기기로 접속한 경우, 마지막
 * 세션이 떠날 때만 online=false 가 전달된다.
 */
public record PresenceEvent(
        @JsonProperty("user_id") long userId,
        boolean online
) {
}

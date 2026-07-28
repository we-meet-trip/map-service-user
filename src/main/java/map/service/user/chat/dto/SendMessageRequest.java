package map.service.user.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SendMessageRequest — 메시지 전송 요청 본문(WebSocket/REST 공용)
 *
 * content 는 보낼 텍스트, clientMsgId 는 클라이언트가 낙관적 렌더링·중복 제거에 쓰는
 * 임의 식별자로 서버는 저장하지 않고 그대로 무시한다(에코가 필요하면 상위에서 처리).
 */
public record SendMessageRequest(
        String content,
        @JsonProperty("client_msg_id") String clientMsgId
) {
}

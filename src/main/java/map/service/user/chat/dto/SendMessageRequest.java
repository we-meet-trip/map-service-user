package map.service.user.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SendMessageRequest — 메시지 전송 요청 본문(WebSocket/REST 공용)
 *
 * content 는 보낼 텍스트, clientMsgId 는 클라이언트가 낙관적 렌더링·중복 제거에 쓰는
 * 임의 식별자다. 서버는 이 값을 저장하지 않지만 응답과 방 방송에 그대로 되돌려 준다.
 * 보낸 사람도 자기 메시지를 방송으로 받기 때문에, 되돌려 주지 않으면 화면에 미리 그려 둔
 * 말풍선과 짝지을 수단이 없어 같은 말이 두 번 남는다.
 */
public record SendMessageRequest(
        String content,
        @JsonProperty("client_msg_id") String clientMsgId
) {
}

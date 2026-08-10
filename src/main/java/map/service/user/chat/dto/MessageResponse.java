package map.service.user.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/**
 * MessageResponse — 메시지 단건 응답
 *
 * 텍스트/시스템 메시지를 클라이언트에 전달한다. senderId 는 시스템 메시지면 null,
 * systemPayload 는 카드형 시스템 메시지의 구조화 데이터(없으면 null)다. unreadCount 는
 * 이 메시지를 아직 읽지 않은 ACTIVE 참가자 수로, 카톡식 "안 읽은 인원수" 표시에 쓰인다.
 *
 * clientMsgId 는 보낸 쪽이 붙여 온 임시 식별자를 그대로 돌려준 값이다. 보낸 사람도 자기
 * 메시지를 방 방송으로 다시 받는데, 서버가 매기는 식별자는 seq 뿐이라 화면에 미리 그려 둔
 * 말풍선과 돌아온 것을 짝지을 수단이 없어 같은 말이 두 번 보인다. 이 값이 그 짝을 이어 준다.
 * 저장하지 않으므로 히스토리로 다시 읽으면 비어 있다 — 그때는 이미 짝지을 대상이 없다.
 */
public record MessageResponse(
        @JsonProperty("room_id") long roomId,
        long seq,
        @JsonProperty("sender_id") Long senderId,
        String type,
        String content,
        @JsonProperty("system_payload") JsonNode systemPayload,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("unread_count") long unreadCount,
        @JsonProperty("client_msg_id") String clientMsgId
) {
}

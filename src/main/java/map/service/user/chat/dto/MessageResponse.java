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
 */
public record MessageResponse(
        @JsonProperty("room_id") long roomId,
        long seq,
        @JsonProperty("sender_id") Long senderId,
        String type,
        String content,
        @JsonProperty("system_payload") JsonNode systemPayload,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("unread_count") long unreadCount
) {
}

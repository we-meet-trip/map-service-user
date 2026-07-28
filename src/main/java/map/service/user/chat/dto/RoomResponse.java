package map.service.user.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/**
 * RoomResponse — 채팅방 단건 응답
 *
 * 방의 식별 정보와 현재 상태를 클라이언트에 전달한다. readOnly 는 보관 전용 전환
 * 여부, latestSeq 는 방의 마지막 메시지 순번(미읽음 계산·초기 스크롤 위치의 기준),
 * participantCount 는 현재 ACTIVE 참가자 수이다.
 */
public record RoomResponse(
        @JsonProperty("room_id") long roomId,
        @JsonProperty("schedule_id") long scheduleId,
        @JsonProperty("owner_id") long ownerId,
        String title,
        @JsonProperty("read_only") boolean readOnly,
        @JsonProperty("expires_at") OffsetDateTime expiresAt,
        @JsonProperty("participant_count") int participantCount,
        @JsonProperty("latest_seq") long latestSeq
) {
}

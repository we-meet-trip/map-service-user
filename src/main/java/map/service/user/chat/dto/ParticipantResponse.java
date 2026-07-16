package map.service.user.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/**
 * ParticipantResponse — 참가자 단건 응답
 *
 * 참가자 목록 표시에 쓰인다. nickname 은 화면에 보일 표시 이름(사용자 프로필에서 조회),
 * role 은 OWNER/MEMBER, status 는 ACTIVE/LEFT/KICKED 의 문자열이며, lastReadSeq 는 해당
 * 참가자가 마지막으로 읽은 메시지 순번이다. 클라이언트는 이 목록으로 senderId·시스템
 * 메시지의 user_id 를 이름으로 대응시킨다.
 */
public record ParticipantResponse(
        @JsonProperty("user_id") long userId,
        String nickname,
        String role,
        String status,
        @JsonProperty("last_read_seq") long lastReadSeq,
        @JsonProperty("joined_at") OffsetDateTime joinedAt
) {
}

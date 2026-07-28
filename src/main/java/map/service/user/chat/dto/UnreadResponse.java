package map.service.user.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * UnreadResponse — 호출자 기준 미읽음 요약 응답
 *
 * 읽음 처리(mark-read)와 미읽음 조회의 공통 응답이다. unreadCount 는 호출자가 아직 읽지
 * 않은 메시지 수(latestSeq - lastReadSeq, 음수면 0), lastReadSeq 는 호출자의 마지막 읽은
 * 순번, latestSeq 는 방의 최신 메시지 순번이다.
 */
public record UnreadResponse(
        @JsonProperty("room_id") long roomId,
        @JsonProperty("unread_count") long unreadCount,
        @JsonProperty("last_read_seq") long lastReadSeq,
        @JsonProperty("latest_seq") long latestSeq
) {
}

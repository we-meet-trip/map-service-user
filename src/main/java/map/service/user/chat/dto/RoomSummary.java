package map.service.user.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RoomSummary — 내 채팅방 목록의 한 항목
 *
 * 목록 화면에 필요한 요약만 담는다. unreadCount 는 호출자 기준 안 읽은 메시지 수로,
 * 방의 메시지 순번이 1 부터 빈틈없이 증가하는 성질을 이용해 latestSeq 에서 호출자의
 * 마지막 읽은 순번을 뺀 값으로 구한다(음수면 0). lastMessage 는 최신 메시지 미리보기.
 */
public record RoomSummary(
        @JsonProperty("room_id") long roomId,
        @JsonProperty("schedule_id") long scheduleId,
        String title,
        @JsonProperty("read_only") boolean readOnly,
        @JsonProperty("unread_count") long unreadCount,
        @JsonProperty("last_message") String lastMessage,
        @JsonProperty("latest_seq") long latestSeq
) {
}

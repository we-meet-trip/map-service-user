package map.service.user.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/**
 * RoomSummary — 내 채팅방 목록의 한 항목
 *
 * 목록 화면에 필요한 요약만 담는다. unreadCount 는 호출자 기준 안 읽은 메시지 수로,
 * 방의 메시지 순번이 1 부터 빈틈없이 증가하는 성질을 이용해 latestSeq 에서 호출자의
 * 마지막 읽은 순번을 뺀 값으로 구한다(음수면 0). lastMessage 는 최신 메시지 미리보기.
 *
 * participantCount 와 lastMessageAt 은 목록 화면이 사람 수와 시간을 함께 보여주고
 * 최근 대화 순으로 정렬하기 때문에 함께 싣는다. 이 둘이 없으면 화면이 방을 열어 봐야만
 * 알 수 있는 값을 그리려 들게 된다. lastMessageAt 은 아직 아무도 말하지 않은 방에서 비어 있다.
 */
public record RoomSummary(
        @JsonProperty("room_id") long roomId,
        @JsonProperty("schedule_id") Long scheduleId,
        String title,
        @JsonProperty("read_only") boolean readOnly,
        @JsonProperty("unread_count") long unreadCount,
        @JsonProperty("last_message") String lastMessage,
        @JsonProperty("last_message_at") OffsetDateTime lastMessageAt,
        @JsonProperty("latest_seq") long latestSeq,
        @JsonProperty("participant_count") int participantCount
) {
}

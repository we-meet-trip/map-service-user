package map.service.user.chat.ws;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ReadEvent — 읽음 이벤트 payload
 *
 * 어떤 사용자가 어디까지 읽었는지 알린다. 클라이언트는 이 사용자의 읽음 위치를 lastReadSeq
 * 로 갱신하고, seq 가 그 값 이하인 메시지들의 안 읽은 인원수 표시를 낮춘다.
 */
public record ReadEvent(
        @JsonProperty("user_id") long userId,
        @JsonProperty("last_read_seq") long lastReadSeq
) {
}

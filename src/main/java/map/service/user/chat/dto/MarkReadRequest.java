package map.service.user.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * MarkReadRequest — 읽음 처리 요청 본문
 *
 * 호출자가 어디까지 읽었는지 알린다. lastReadSeq 는 읽은 마지막 메시지 순번이며, 서버는
 * 현재 읽음 위치보다 클 때만 단조 전진시키고 방의 최신 순번을 넘지 않도록 보정한다.
 */
public record MarkReadRequest(
        @JsonProperty("last_read_seq") long lastReadSeq
) {
}

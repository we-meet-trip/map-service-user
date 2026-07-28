package map.service.user.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * HistoryResponse — 메시지 히스토리 한 페이지 응답
 *
 * messages 는 seq 내림차순(최신 먼저)이며, nextCursor 는 다음(더 과거) 페이지를 요청할
 * 때 넘길 before_seq 값이다. 이번 페이지가 요청 개수보다 적으면(더 과거 메시지 없음)
 * nextCursor 는 null 이다.
 */
public record HistoryResponse(
        List<MessageResponse> messages,
        @JsonProperty("next_cursor") Long nextCursor
) {
}

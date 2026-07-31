package map.service.user.schedule.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * ScheduleSummary — 일정 목록의 한 줄
 *
 * 목록 화면은 카드에 제목과 기간만 그리고, 방문지는 상세에서 받아 온다.
 * 그래서 여기에는 payload 를 펼치지 않는다 — 목록 한 번에 수십 건의 추천
 * 결과를 통째로 직렬화하면 응답이 커지고 조립 비용(도로 경로 조회 포함)도
 * 건수만큼 늘어난다.
 *
 * scheduleId: 일정 식별자. JSON key "schedule_id". 상세/삭제의 경로 변수.
 * title: 일정 제목. 저장 시 지정하지 않았으면 null.
 * dateStart / dateEnd: 여행 기간. JSON key "date_start" / "date_end".
 * createdAt: 저장 시각. JSON key "created_at". 목록 정렬 근거를 client 가
 *            함께 보여줄 수 있게 내려준다.
 */
public record ScheduleSummary(
        @JsonProperty("schedule_id") Long scheduleId,
        String title,
        @JsonProperty("date_start") LocalDate dateStart,
        @JsonProperty("date_end") LocalDate dateEnd,
        @JsonProperty("created_at") OffsetDateTime createdAt
) {
}

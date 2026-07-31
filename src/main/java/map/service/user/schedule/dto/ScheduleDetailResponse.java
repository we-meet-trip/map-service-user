package map.service.user.schedule.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import map.service.user.trip.dto.TripStop;

/**
 * ScheduleDetailResponse — 저장된 일정 상세 응답 본문
 *
 * stops 는 생성 직후 응답(TripGenerateResponse)과 같은 조립기를 거친 같은
 * 타입이다. client 가 결과 화면 렌더링 코드를 그대로 재사용할 수 있게
 * 필드 이름과 의미를 맞춘다.
 *
 * 날씨는 담지 않는다. 저장 시점의 예보는 다시 열어 볼 때 이미 지난 정보라
 * 그대로 보여주면 틀린 안내가 된다. 필요해지면 조회 시점 기준으로 새로
 * 받아 오는 편이 맞다.
 *
 * scheduleId / jobId: 일정 식별자와 원본 추천 작업 식별자.
 * title: 일정 제목. 미지정 시 null.
 * dateStart / dateEnd: 여행 기간.
 * transport: 저장 당시 이동수단. 메타 없이 저장된 일정은 null.
 * totalDurationMinutes: stops 의 이동 시간 합.
 * stops: 방문 순서대로의 방문지 목록.
 * createdAt: 저장 시각.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScheduleDetailResponse(
        @JsonProperty("schedule_id") Long scheduleId,
        @JsonProperty("job_id") String jobId,
        String title,
        @JsonProperty("date_start") LocalDate dateStart,
        @JsonProperty("date_end") LocalDate dateEnd,
        String transport,
        @JsonProperty("total_duration_minutes") int totalDurationMinutes,
        List<TripStop> stops,
        @JsonProperty("created_at") OffsetDateTime createdAt
) {
}

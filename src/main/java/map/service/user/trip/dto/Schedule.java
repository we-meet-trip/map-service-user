package map.service.user.trip.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Schedule — trip 생성 요청의 일정/활동 시간대 (client Step 1)
 *
 * TripGenerateRequest 의 schedule 필드로 중첩되어 사용된다.
 * client 가 보내는 JSON 키는 snake_case 이며 본 record 가 그대로 매핑한다.
 *
 * startDate: 여행 시작일. JSON key "start_date", "yyyy-MM-dd" 문자열. @NotNull.
 * endDate:   여행 종료일. JSON key "end_date",   "yyyy-MM-dd" 문자열. @NotNull.
 * activeStartHour: 일별 활동 시작 시각(0~24 정수). JSON key "active_start_hour".
 * activeEndHour:   일별 활동 종료 시각(0~24 정수). JSON key "active_end_hour".
 */
public record Schedule(
        @JsonProperty("start_date")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @NotNull LocalDate startDate,

        @JsonProperty("end_date")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        @NotNull LocalDate endDate,

        @JsonProperty("active_start_hour")
        @NotNull @Min(0) @Max(24) Integer activeStartHour,

        @JsonProperty("active_end_hour")
        @NotNull @Min(0) @Max(24) Integer activeEndHour
) {
}

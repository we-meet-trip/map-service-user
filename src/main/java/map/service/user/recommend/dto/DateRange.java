package map.service.user.recommend.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * DateRange — 추천 요청의 여행 기간/시간대
 *
 * RecommendRequest 의 date 필드로 중첩되어 사용된다.
 * 모든 필드 @NotNull. JSON 키는 snake_case 로 매핑된다.
 *
 * dateStart: 여행 시작일. JSON key "date_start", "yyyy-MM-dd" 문자열.
 * dateEnd:   여행 종료일. JSON key "date_end",   "yyyy-MM-dd" 문자열.
 * timeStart: 일별 시작 시각. JSON key "time_start", "HH:mm:ss" 문자열.
 * timeEnd:   일별 종료 시각. JSON key "time_end",   "HH:mm:ss" 문자열.
 */
public record DateRange(
        @JsonProperty("date_start") @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") @NotNull LocalDate dateStart,
        @JsonProperty("date_end") @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") @NotNull LocalDate dateEnd,
        @JsonProperty("time_start") @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss") @NotNull LocalTime timeStart,
        @JsonProperty("time_end") @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "HH:mm:ss") @NotNull LocalTime timeEnd
) {
}

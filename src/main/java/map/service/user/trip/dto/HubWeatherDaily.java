package map.service.user.trip.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * HubWeatherDaily — hub /v1/weather 응답의 daily 원소 (역직렬화용)
 *
 * hub 의 WeatherDailyItem 형식을 그대로 받는다. temp/precip 는 단기/중기/결측에
 * 따라 null 일 수 있으므로 박스 타입으로 둔다(null 이면 TripService 가 제외).
 *
 * date: 날짜.
 * tempMin/tempMax: 최저/최고 기온(℃). JSON key "temp_min"/"temp_max". null 가능.
 * precipitationProb: 강수 확률(%). JSON key "precipitation_prob". null 가능.
 * skyCondition: 하늘 상태 한글 텍스트(예: "맑음"). JSON key "sky_condition". null 가능.
 * source: 데이터 출처("short_term" | "mid_land+mid_temp").
 */
public record HubWeatherDaily(
        LocalDate date,
        @JsonProperty("temp_min") Integer tempMin,
        @JsonProperty("temp_max") Integer tempMax,
        @JsonProperty("precipitation_prob") Integer precipitationProb,
        @JsonProperty("sky_condition") String skyCondition,
        String source,
        @JsonProperty("source_at") OffsetDateTime sourceAt,
        @JsonProperty("captured_at") OffsetDateTime capturedAt,
        @JsonProperty("expires_at") OffsetDateTime expiresAt
) {
    public HubWeatherDaily(LocalDate date, Integer min, Integer max, Integer pop, String sky, String source) {
        this(date, min, max, pop, sky, source, null, null, null);
    }
}

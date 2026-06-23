package map.service.user.trip.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

/**
 * HubWeatherResponse — hub GET /v1/weather 응답 (역직렬화용)
 *
 * HubWeatherClient 가 받는 형식. hub 의 WeatherResponse 와 1:1.
 * spring.jackson.fail-on-unknown-properties=false 이므로 추가 필드는 무시된다.
 *
 * province/city: 응답 기준 행정구역.
 * daily: 일자별 날씨 항목.
 * missingDates: 데이터를 만들 수 없던 날짜. JSON key "missing_dates".
 */
public record HubWeatherResponse(
        String province,
        String city,
        List<HubWeatherDaily> daily,
        @JsonProperty("missing_dates") List<LocalDate> missingDates
) {
}

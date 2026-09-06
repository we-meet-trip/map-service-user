package map.service.user.trip.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * WeatherForecastItem — 일자별 날씨 (client weather_forecast 의 원소)
 *
 * BFF 가 hub /v1/weather 의 daily 항목을 client 형식으로 변환해 만든다(결정 D-6).
 * 필드별 결측은 null로 보존한다. 부분 예보를 버리거나 0으로 대체하지 않는다.
 *
 * date: 날짜 "yyyy-MM-dd".
 * condition: sunny/cloudy/rainy/snowy 중 하나. hub sky_condition → 매핑(R-4).
 * tempHigh: 최고기온(℃). JSON key "temp_high". hub temp_max.
 * tempLow:  최저기온(℃). JSON key "temp_low".  hub temp_min.
 * precipitationProbability: 강수확률(%). JSON key "precipitation_probability". hub precipitation_prob.
 */
public record WeatherForecastItem(
        String date,
        String condition,
        @JsonProperty("temp_high") Integer tempHigh,
        @JsonProperty("temp_low") Integer tempLow,
        @JsonProperty("precipitation_probability") Integer precipitationProbability
) {
}

package map.service.user.trip.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * WeatherForecastItem — 일자별 날씨 (client weather_forecast 의 원소)
 *
 * BFF 가 hub /v1/weather 의 daily 항목을 client 형식으로 변환해 만든다(결정 D-6).
 * client 가 모든 필드를 int 로 엄격 캐스팅하므로, temp/precip 가 null 인 hub
 * 항목은 TripService 가 아예 제외한다(크래시 방지). 따라서 본 record 의
 * 정수 필드는 항상 채워진 값만 들어온다.
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
        @JsonProperty("temp_high") int tempHigh,
        @JsonProperty("temp_low") int tempLow,
        @JsonProperty("precipitation_probability") int precipitationProbability
) {
}

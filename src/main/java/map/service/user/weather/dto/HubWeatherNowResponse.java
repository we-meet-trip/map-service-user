package map.service.user.weather.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * HubWeatherNowResponse — hub GET /v1/weather/now 응답 (역직렬화용)
 *
 * hub 가 실황·예보·대기오염을 합쳐 돌려주는 형태와 1:1 로 맞춘 형태다.
 * 알 수 없는 필드는 무시하도록 설정돼 있어 hub 가 항목을 늘려도 깨지지 않는다.
 *
 * nx/ny: 요청 좌표가 속한 격자.
 * province/city: 격자로 역조회한 행정구역. 매칭이 없으면 null.
 * now: 지금 관측값. hub 가 항상 채운다.
 * yesterday: 어제 같은 시간대 기록. 기록이 없으면 null.
 * today: 오늘 예보 요약. 행정구역을 못 찾으면 null.
 * air: 대기오염 정보. 조회 실패면 null.
 */
public record HubWeatherNowResponse(
        int nx,
        int ny,
        String province,
        String city,
        Observation now,
        Yesterday yesterday,
        Today today,
        Air air
) {

    /**
     * 관측값.
     *
     * tempC: 관측 기온(℃). JSON key "temp_c".
     * pty: 강수 형태 코드. 0 이면 강수 없음.
     * baseDate/baseTime: 관측 발표 일자·시각.
     */
    public record Observation(
            @JsonProperty("temp_c") double tempC,
            Integer pty,
            @JsonProperty("base_date") String baseDate,
            @JsonProperty("base_time") String baseTime
    ) {
    }

    /**
     * 어제 같은 시간대 기록.
     *
     * tempC: 그때 관측된 기온(℃). JSON key "temp_c".
     * hourKst: 실제 기록이 남아 있던 시각(시). JSON key "hour_kst".
     */
    public record Yesterday(
            @JsonProperty("temp_c") double tempC,
            @JsonProperty("hour_kst") int hourKst
    ) {
    }

    /**
     * 오늘 예보 요약.
     *
     * tempMax/tempMin: 일 최고/최저 기온(℃).
     * precipitationProb: 강수 확률(%).
     * skyCondition: 하늘 상태 텍스트.
     */
    public record Today(
            @JsonProperty("temp_max") Integer tempMax,
            @JsonProperty("temp_min") Integer tempMin,
            @JsonProperty("precipitation_prob") Integer precipitationProb,
            @JsonProperty("sky_condition") String skyCondition
    ) {
    }

    /**
     * 대기오염 측정값과 등급.
     *
     * pm10/pm25: 농도(㎍/㎥).
     * pm10Grade/pm25Grade: 한글 등급.
     * station: 값을 채택한 측정소 이름.
     */
    public record Air(
            Integer pm10,
            Integer pm25,
            @JsonProperty("pm10_grade") String pm10Grade,
            @JsonProperty("pm25_grade") String pm25Grade,
            String station
    ) {
    }
}

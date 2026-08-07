package map.service.user.weather.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * WeatherHomeResponse — 홈 화면 날씨 카드 응답
 *
 * 카드가 그리는 항목을 그대로 한 번에 담는다. 화면이 여러 출처를 각각
 * 호출하지 않아도 되도록 실황·예보·대기오염을 서버에서 합쳐 내려준다.
 *
 * 값이 없는 항목은 키째 빠진다. 화면은 부재를 전제로 그려야 하며, 없는
 * 항목의 자리를 비워 두는 대신 그 줄을 통째로 생략한다.
 *
 * temp: 지금 기온(℃).
 * pty: 강수 형태 코드. 0 이면 강수 없음. 아이콘 결정에 쓴다.
 * sky: 하늘 상태 텍스트(예: "맑음"). 예보가 없으면 빠진다.
 * yesterdayDiff: 어제 같은 시간대 대비 기온 차(℃). 어제 기록이 없으면
 *                빠지고, 그때 화면은 비교 문구를 그리지 않는다.
 *                JSON key "yesterday_diff".
 * tempMax/tempMin: 오늘 최고/최저 기온(℃). JSON key "temp_max"/"temp_min".
 * pop: 강수 확률(%).
 * pm10/pm25: 미세먼지·초미세먼지 농도(㎍/㎥).
 * pm10Grade/pm25Grade: 농도에 대응하는 한글 등급.
 * attribution: 출처 표기 문구. 화면 하단에 그대로 노출한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WeatherHomeResponse(
        double temp,
        Integer pty,
        String sky,
        @JsonProperty("yesterday_diff") Double yesterdayDiff,
        @JsonProperty("temp_max") Integer tempMax,
        @JsonProperty("temp_min") Integer tempMin,
        Integer pop,
        Integer pm10,
        Integer pm25,
        @JsonProperty("pm10_grade") String pm10Grade,
        @JsonProperty("pm25_grade") String pm25Grade,
        String attribution
) {
}

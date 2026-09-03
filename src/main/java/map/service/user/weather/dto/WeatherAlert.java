package map.service.user.weather.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * WeatherAlert — 저장된 일정에 붙는 날씨 변화 알림
 *
 * 일정 저장 시점의 예보와 지금 예보가 실내/야외 판단이 갈릴 만큼 달라졌을 때
 * schedules.weather_alert 에 담기고, 일정 조회 응답에 그대로 실려 나간다.
 * 앱은 이 값이 있으면 배너를 띄우고, 재추천 버튼으로 replan 을 부른다.
 *
 * kind: rain_appeared(비 예보가 새로 생김 — 실내 위주로 다시 짤 만함)
 *       | rain_cleared(비 예보가 사라짐 — 야외로 되돌릴 만함)
 * date: 변화가 생긴 일자.
 * popBefore / popAfter: 그 일자의 강수확률(%) 전후. 값이 없으면 null.
 * skyBefore / skyAfter: 그 일자의 하늘 상태 전후.
 * detectedAt: 변화를 확인한 시각. 같은 알림이 다시 뜨는지 판단하는 근거이자
 *             화면에 "언제 기준" 을 적기 위한 값이다.
 */
public record WeatherAlert(
        String kind,
        LocalDate date,
        @JsonProperty("pop_before") Integer popBefore,
        @JsonProperty("pop_after") Integer popAfter,
        @JsonProperty("sky_before") String skyBefore,
        @JsonProperty("sky_after") String skyAfter,
        @JsonProperty("detected_at") OffsetDateTime detectedAt
) {
    public static final String RAIN_APPEARED = "rain_appeared";
    public static final String RAIN_CLEARED = "rain_cleared";
}

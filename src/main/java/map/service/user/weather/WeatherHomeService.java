package map.service.user.weather;

import map.service.user.weather.dto.HubWeatherNowResponse;
import map.service.user.weather.dto.WeatherHomeResponse;
import org.springframework.stereotype.Service;

/**
 * WeatherHomeService — 홈 화면 날씨 카드 조립
 *
 * hub 가 실황·예보·대기오염을 각각의 묶음으로 내려주면, 화면이 한 번에 읽을
 * 수 있는 평평한 형태로 접는다. 어제 대비 기온 차처럼 화면이 매번 계산해야
 * 하는 값도 여기서 미리 구해 넘긴다.
 *
 * 위치 취급: 좌표는 hub 호출 인자로만 쓰고 저장하지 않으며 로그에도 남기지
 * 않는다. 이 클래스는 좌표를 필드로 들고 있지 않다.
 */
@Service
public class WeatherHomeService {

    /** 화면 하단에 그대로 노출하는 출처 표기. */
    private static final String ATTRIBUTION = "기상청, 한국환경공단 제공";

    private final HubWeatherNowClient client;

    public WeatherHomeService(HubWeatherNowClient client) {
        this.client = client;
    }

    /**
     * 좌표로 홈 카드 응답을 만든다.
     *
     * lat/lng: 기기 위치.
     *
     * hub 호출이 실패하거나 실황이 비어 있으면 WeatherUnavailableException.
     * 예보·대기오염이 비는 것은 정상 상황이라 해당 항목만 빠진다.
     */
    public WeatherHomeResponse fetchHome(double lat, double lng) {
        HubWeatherNowResponse hub;
        try {
            hub = client.fetchNow(lat, lng);
        } catch (RuntimeException e) {
            throw new WeatherUnavailableException("hub weather now failed", e);
        }
        if (hub == null || hub.now() == null) {
            throw new WeatherUnavailableException("hub returned no observation");
        }

        double temp = hub.now().tempC();
        Double yesterdayDiff = null;
        if (hub.yesterday() != null) {
            // 소수 첫째 자리까지만 둔다 — 화면이 "1.8도 낮아요" 식으로 한 자리만
            // 쓰는데, 부동소수 연산 결과를 그대로 내보내면 자릿수가 길어진다.
            yesterdayDiff =
                    Math.round((temp - hub.yesterday().tempC()) * 10.0) / 10.0;
        }

        HubWeatherNowResponse.Today today = hub.today();
        HubWeatherNowResponse.Air air = hub.air();

        return new WeatherHomeResponse(
                temp,
                hub.now().pty(),
                today != null ? today.skyCondition() : null,
                yesterdayDiff,
                today != null ? today.tempMax() : null,
                today != null ? today.tempMin() : null,
                today != null ? today.precipitationProb() : null,
                air != null ? air.pm10() : null,
                air != null ? air.pm25() : null,
                air != null ? air.pm10Grade() : null,
                air != null ? air.pm25Grade() : null,
                ATTRIBUTION);
    }
}

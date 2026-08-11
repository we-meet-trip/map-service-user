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
     * hub 호출 자체가 실패하면 WeatherUnavailableException.
     *
     * 항목이 비어 오는 것은 실패가 아니다. hub 는 미리 받아 둔 값을 돌려주고,
     * 그 값이 너무 오래됐으면 해당 항목을 비워 보낸다. 기온이 비었다고 카드를
     * 통째로 실패시키면 남아 있는 예보·미세먼지까지 함께 사라진다 — 있는
     * 것만 담아 보내고 무엇을 그릴지는 화면이 정한다.
     */
    public WeatherHomeResponse fetchHome(double lat, double lng) {
        HubWeatherNowResponse hub;
        try {
            hub = client.fetchNow(lat, lng);
        } catch (RuntimeException e) {
            throw new WeatherUnavailableException("hub weather now failed", e);
        }
        if (hub == null) {
            throw new WeatherUnavailableException("hub returned no body");
        }

        HubWeatherNowResponse.Observation now = hub.now();
        Double temp = now != null ? now.tempC() : null;
        Double yesterdayDiff = null;
        if (temp != null && hub.yesterday() != null) {
            // 소수 첫째 자리까지만 둔다 — 화면이 "1.8도 낮아요" 식으로 한 자리만
            // 쓰는데, 부동소수 연산 결과를 그대로 내보내면 자릿수가 길어진다.
            yesterdayDiff =
                    Math.round((temp - hub.yesterday().tempC()) * 10.0) / 10.0;
        }

        HubWeatherNowResponse.Today today = hub.today();
        HubWeatherNowResponse.Air air = hub.air();

        return new WeatherHomeResponse(
                temp,
                now != null ? now.pty() : null,
                today != null ? today.skyCondition() : null,
                yesterdayDiff,
                today != null ? today.tempMax() : null,
                today != null ? today.tempMin() : null,
                today != null ? today.precipitationProb() : null,
                air != null ? air.pm10() : null,
                air != null ? air.pm25() : null,
                air != null ? air.pm10Grade() : null,
                air != null ? air.pm25Grade() : null,
                now != null ? now.observedAt() : null,
                air != null ? air.observedAt() : null,
                ATTRIBUTION);
    }
}

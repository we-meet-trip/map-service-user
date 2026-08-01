package map.service.user.weather;

import map.service.user.weather.dto.HubWeatherNowResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HubWeatherNowClient — hub /v1/weather/now 호출 어댑터 (경계 B3)
 *
 * 홈 화면 날씨 카드가 필요로 하는 현재 날씨를 hub 에서 받아 온다.
 * 여기서는 실패를 흡수하지 않는다 — 카드의 본체인 지금 기온이 없으면 그릴
 * 것이 없어서, 부분 응답을 만들어 내려보내는 대신 호출 측이 오류를 그대로
 * 다루게 한다(추천에 곁들이는 날씨와 성격이 다르다).
 *
 * client: @Qualifier("hubRestClient") RestClient. base URL·타임아웃·내부
 * 토큰 헤더는 이미 구성돼 있어 그대로 쓴다.
 */
@Component
public class HubWeatherNowClient {

    private static final Logger log =
            LoggerFactory.getLogger(HubWeatherNowClient.class);

    private final RestClient client;

    public HubWeatherNowClient(@Qualifier("hubRestClient") RestClient client) {
        this.client = client;
    }

    /**
     * hub GET /v1/weather/now 호출.
     *
     * lat/lng: 기기 위치. 좌표는 이 호출에만 쓰고 저장하지 않는다.
     *
     * 반환: hub 응답. 본문이 비어 오면 null 을 돌려주고 호출 측이 판단한다.
     */
    public HubWeatherNowResponse fetchNow(double lat, double lng) {
        // 로그에는 좌표를 남기지 않는다 — 기기 위치가 로그에 축적되지 않도록
        // 실패 사유만 기록한다.
        try {
            return client.get()
                    .uri(uri -> uri.path("/v1/weather/now")
                            .queryParam("lat", lat)
                            .queryParam("lng", lng)
                            .build())
                    .retrieve()
                    .body(HubWeatherNowResponse.class);
        } catch (RuntimeException e) {
            log.warn("hub /v1/weather/now failed reason={}", e.getMessage());
            throw e;
        }
    }
}

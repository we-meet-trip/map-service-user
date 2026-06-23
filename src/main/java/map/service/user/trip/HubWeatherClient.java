package map.service.user.trip;

import java.time.LocalDate;
import java.util.List;
import map.service.user.trip.dto.HubWeatherResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HubWeatherClient — hub /v1/weather 호출 어댑터 (경계 B3)
 *
 * trip 응답의 weather_forecast 를 채우기 위해 hub 의 날씨 엔드포인트를 동기 호출한다.
 * 날씨는 추천의 보조 정보이므로 best-effort 다 — 호출이 실패(4xx/5xx/네트워크)하면
 * 예외를 전파하지 않고 비어 있는 응답(daily 없음)을 돌려준다. 이렇게 하면 추천 자체가
 * 성공한 요청을 날씨 때문에 중단시키지 않는다(무중단 원칙, client 는 빈 배열을 정상 처리).
 *
 * client: @Qualifier("hubRestClient") RestClient. base URL/타임아웃은 HubClientConfig 구성.
 */
@Component
public class HubWeatherClient {

    private static final Logger log = LoggerFactory.getLogger(HubWeatherClient.class);

    private final RestClient client;

    public HubWeatherClient(@Qualifier("hubRestClient") RestClient client) {
        this.client = client;
    }

    /**
     * hub GET /v1/weather 호출. 실패 시 빈 응답을 반환(예외 미전파).
     *
     * province/city/date_start/date_end 를 쿼리 파라미터로 전달한다.
     * LocalDate 는 ISO(yyyy-MM-dd) 로 직렬화되어 hub 의 date 파싱과 정합한다.
     *
     * province/city: 광역시도/시군구.
     * start/end: 조회 구간(양끝 포함).
     */
    public HubWeatherResponse fetchWeather(
            String province, String city, LocalDate start, LocalDate end
    ) {
        try {
            HubWeatherResponse res = client.get()
                    .uri(uri -> uri.path("/v1/weather")
                            .queryParam("province", province)
                            .queryParam("city", city)
                            .queryParam("date_start", start)
                            .queryParam("date_end", end)
                            .build())
                    .retrieve()
                    .body(HubWeatherResponse.class);
            if (res == null) {
                return empty(province, city);
            }
            return res;
        } catch (RuntimeException e) {
            // region 미해석(404) · hub 다운 · 타임아웃 등. 추천은 이미 성공했을 수 있으므로
            // 날씨만 비우고 진행한다(서버 로그에만 원인 기록).
            log.warn("hub /v1/weather failed province={} city={} reason={}",
                    province, city, e.getMessage());
            return empty(province, city);
        }
    }

    /** 날씨 데이터가 없을 때의 빈 응답(daily/missing_dates 비어 있음). */
    private static HubWeatherResponse empty(String province, String city) {
        return new HubWeatherResponse(province, city, List.of(), List.of());
    }
}

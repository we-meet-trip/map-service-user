package map.service.user.trip;

import java.util.List;
import map.service.user.trip.dto.HubDirectionsDtos.BatchRequest;
import map.service.user.trip.dto.HubDirectionsDtos.BatchResponse;
import map.service.user.trip.dto.HubDirectionsDtos.LegReq;
import map.service.user.trip.dto.HubDirectionsDtos.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HubDirectionsClient — hub POST /v1/directions/batch 호출 어댑터 (경계 B3)
 *
 * stop 간 이동 구간의 도로 추종 경로를 hub 에 일괄 요청한다. 경로는 추천의
 * 보조 시각화이므로 best-effort 다 — 호출이 실패(4xx/5xx/네트워크/타임아웃)하면
 * 예외를 전파하지 않고 null 을 반환한다. 호출측(TripService)은 null 을 받으면
 * 전 구간을 직선 폴백으로 처리한다(무중단 원칙: 경로 때문에 추천을 막지 않음).
 *
 * hub 는 전 구간 실패여도 200 + routes 전부 null 로 응답하므로, 여기서 null 은
 * "배치 호출 자체가 실패"한 경우만을 뜻한다(부분 실패는 routes 원소 null).
 *
 * client: @Qualifier("hubRestClient") RestClient — base URL/타임아웃/
 *         X-Internal-Token 은 HubClientConfig 가 구성(HubWeatherClient 와 동일 빈).
 */
@Component
public class HubDirectionsClient {

    private static final Logger log =
            LoggerFactory.getLogger(HubDirectionsClient.class);

    private final RestClient client;

    public HubDirectionsClient(@Qualifier("hubRestClient") RestClient client) {
        this.client = client;
    }

    /**
     * hub POST /v1/directions/batch 호출. 실패 시 null(전 구간 폴백 유도).
     *
     * mode: 이동수단(walk|bicycle|scooter). bus 는 호출측이 애초에 부르지 않는다.
     * legs: 구간 목록(1~20). 반환 routes 는 legs 와 같은 길이·인덱스.
     * @return 배치 응답의 routes(원소는 실패 시 null), 또는 배치 자체 실패 시 null.
     */
    public List<Route> fetchRoutes(String mode, List<LegReq> legs) {
        try {
            BatchResponse res = client.post()
                    .uri("/v1/directions/batch")
                    .body(new BatchRequest(mode, legs))
                    .retrieve()
                    .body(BatchResponse.class);
            if (res == null || res.routes() == null) {
                return null;
            }
            return res.routes();
        } catch (RuntimeException e) {
            // hub 다운·타임아웃·역직렬화 실패 등. 경로만 생략하고 진행한다.
            log.warn("hub /v1/directions/batch failed mode={} legs={} reason={}",
                    mode, legs.size(), e.getMessage());
            return null;
        }
    }
}

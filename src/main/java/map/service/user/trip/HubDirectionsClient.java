package map.service.user.trip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import map.service.user.global.crypto.LocationSeal;
import map.service.user.trip.dto.HubDirectionsDtos.BatchRequest;
import map.service.user.trip.dto.HubDirectionsDtos.BatchResponse;
import map.service.user.trip.dto.HubDirectionsDtos.LegReq;
import map.service.user.trip.dto.HubDirectionsDtos.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
 * client: @Qualifier("hubDirectionsRestClient") RestClient — base URL/타임아웃/
 *         X-Internal-Token 은 HubClientConfig 가 구성(경로 전용 요청 시간 예산).
 */
@Component
public class HubDirectionsClient {

    private static final Logger log =
            LoggerFactory.getLogger(HubDirectionsClient.class);

    /**
     * 한 번의 요청에 실어 보낼 구간 수의 상한.
     *
     * hub 요청 본문이 받는 구간 수와 같은 값이어야 한다 — 이 값이 더 크면
     * 요청이 통째로 거절되고, 그러면 부분 실패가 아니라 그 요청이 담당한
     * 구간 전부가 경로 없이 나간다. 일정이 길어질수록 구간 수는 제한 없이
     * 늘어나므로, 여기서 나눠 보내지 않으면 긴 일정은 경로를 하나도 받지
     * 못한다.
     */
    private static final int MAX_LEGS_PER_BATCH = 20;

    private final RestClient client;
    private final LocationSeal seal;

    public HubDirectionsClient(@Qualifier("hubDirectionsRestClient") RestClient client,
            LocationSeal seal) {
        this.seal = seal;
        this.client = client;
    }

    /**
     * 구간들의 도로 추종 경로를 hub 에서 받아 온다. 전부 실패하면 null.
     *
     * 구간이 상한을 넘으면 상한 단위로 나눠 여러 번 보내고, 받은 결과를 원래
     * 구간 순서 그대로 이어 붙인다. 한 번의 요청이 실패해도 그 요청이 담당한
     * 구간만 경로 없이 남고 나머지 구간은 살아남는다 — 나눠 보내는 이유가
     * 실패 범위를 좁히는 데도 있다.
     *
     * mode: 이동수단(walk|bicycle|scooter). bus 는 호출측이 애초에 부르지 않는다.
     * legs: 구간 목록. 반환 routes 는 legs 와 같은 길이·인덱스.
     * @return legs 와 같은 길이의 routes(경로를 못 받은 구간은 원소가 null),
     *         또는 모든 요청이 실패했을 때 null.
     */
    public List<Route> fetchRoutes(String mode, List<LegReq> legs) {
        if (legs == null || legs.isEmpty()) {
            return null;
        }

        List<Route> merged = new ArrayList<>(legs.size());
        boolean anySucceeded = false;

        for (int from = 0; from < legs.size(); from += MAX_LEGS_PER_BATCH) {
            int to = Math.min(from + MAX_LEGS_PER_BATCH, legs.size());
            List<LegReq> batch = legs.subList(from, to);
            List<Route> routes = fetchOneBatch(mode, batch);
            if (routes == null) {
                // 이 요청이 담당한 구간만 경로 없이 남긴다.
                merged.addAll(Collections.nCopies(batch.size(), null));
                continue;
            }
            anySucceeded = true;
            // 응답이 요청보다 짧게 와도 인덱스가 밀리지 않도록 길이를 맞춘다.
            for (int i = 0; i < batch.size(); i++) {
                merged.add(i < routes.size() ? routes.get(i) : null);
            }
        }

        return anySucceeded ? merged : null;
    }

    /**
     * 구간 묶음 하나를 hub 에 보낸다. 실패하면 null.
     *
     * 실패 사유는 종류와 상태 코드만 남긴다. 응답 본문에는 보낸 구간이 그대로
     * 되돌아오는데, 거기에는 방문지 이름과 좌표가 들어 있어 로그에 남기면
     * 사용자의 이동 경로가 그대로 기록된다.
     */
    /**
     * 구간을 감싼 요청을 만든다.
     *
     * 감싸기를 꺼 두었을 때만 구간을 값 그대로 싣는다. 상대가 아직 열 줄
     * 모르는 동안 넘어가기 위한 길이며, 둘을 함께 싣지는 않는다.
     */
    private BatchRequest sealedRequest(String mode, List<LegReq> batch) {
        if (!seal.isEnabled()) {
            return new BatchRequest(mode, null, batch);
        }
        return new BatchRequest(mode, seal.seal(Map.of("legs", batch)), List.of());
    }

    private List<Route> fetchOneBatch(String mode, List<LegReq> batch) {
        try {
            BatchResponse res = client.post()
                    .uri("/v1/directions/batch")
                    .body(sealedRequest(mode, batch))
                    .retrieve()
                    .body(BatchResponse.class);
            if (res == null || res.routes() == null) {
                return null;
            }
            return res.routes();
        } catch (RestClientResponseException e) {
            log.warn("hub /v1/directions/batch failed mode={} legs={} status={}",
                    mode, batch.size(), e.getStatusCode().value());
            return null;
        } catch (RuntimeException e) {
            // hub 다운·타임아웃·역직렬화 실패 등. 경로만 생략하고 진행한다.
            log.warn("hub /v1/directions/batch failed mode={} legs={} cause={}",
                    mode, batch.size(), e.getClass().getSimpleName());
            return null;
        }
    }
}

package map.service.user.transit;

import java.nio.charset.StandardCharsets;
import map.service.user.global.crypto.LocationSeal;
import map.service.user.transit.dto.TransitLaneRequest;
import map.service.user.transit.dto.TransitLaneResponse;
import map.service.user.transit.dto.TransitRouteOptionsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * TransitRouteClient — hub /v1/transit/routes 호출 어댑터 (경계 B3)
 *
 * client 의 통합 길찾기 요청을 hub 의 경로 조회 엔드포인트로 동기 전달한다.
 * SubwayRouteClient 와 같은 패턴이며, RestClient 빈도 그대로 재사용한다.
 *
 * BFF 는 결과를 캐시하지 않는다 — 이유는 SubwayRouteClient 와 같다(hub 가
 * 좌표 단위로 이미 캐시하고 하루 호출 상한까지 관리한다).
 */
@Component
public class TransitRouteClient {

    private static final Logger log =
            LoggerFactory.getLogger(TransitRouteClient.class);

    private final RestClient client;

    /** 오류 응답 본문을 메모리에 읽을 최대 바이트(과대 응답 OOM 방지). */
    private static final int ERROR_BODY_MAX = 4096;

    private final LocationSeal seal;

    public TransitRouteClient(@Qualifier("hubRestClient") RestClient client,
                              LocationSeal seal) {
        this.seal = seal;
        this.client = client;
    }

    /**
     * hub GET /v1/transit/routes 호출.
     *
     * startLat / startLng: 출발 좌표. endLat / endLng: 도착 좌표.
     * mode: 화면이 고른 이동수단(all·subway·bus). 거르는 규칙은 hub 가 정한다 —
     *       여기서 다시 거르면 규칙이 두 곳에 흩어져 한쪽만 고치는 일이 생긴다.
     *
     * 외부 조회 실패는 예외가 아니라 응답의 status 로 온다. 그대로 흘려보내
     * 화면이 "경로 없음"과 "조회 불가"를 구분해 보여줄 수 있게 한다.
     */
    public TransitRouteOptionsResponse fetch(
            double startLat, double startLng, double endLat, double endLng,
            String mode) {
        return client.get()
                .uri(uri -> uri.path("/v1/transit/routes")
                        // 좌표는 감싸서 loc 하나로 보낸다. mode 는 위치
                        // 정보가 아니라 값 그대로 둔다.
                        .queryParam("loc",
                                seal.sealPair(startLat, startLng, endLat, endLng))
                        .queryParam("mode", mode)
                        .build())
                .retrieve()
                .onStatus(
                        HttpStatusCode::isError,
                        (req, res) -> {
                            String body = readBody(res.getBody());
                            throw new TransitRouteException(
                                    res.getStatusCode().value(), body);
                        })
                .body(TransitRouteOptionsResponse.class);
    }

    /**
     * hub POST /v1/transit/routes/lane 호출 — 경로 후보 한 건의 실제 노선 좌표.
     *
     * fetch 와 달리 실패를 예외로 올리지 않고 "unavailable" 로 돌려준다. 이
     * 조회가 실패해도 client 는 이미 가진 정류장 직선을 그대로 그리면 되므로
     * 오류 화면을 띄울 일이 아니다 — HubDirectionsClient 와 같은 판단이다.
     *
     * 실패 로그에는 상태 코드와 예외 종류만 남긴다. mapObj 에는 타고 내린
     * 구간이 담겨 있어, 남기면 사용자의 이동 경로가 로그에 기록된다.
     *
     * BFF 는 결과를 캐시하지 않는다 — hub 가 mapObj 단위로 캐시하고 하루 호출
     * 상한도 관리한다.
     */
    public TransitLaneResponse fetchLane(TransitLaneRequest request) {
        try {
            TransitLaneResponse res = client.post()
                    .uri("/v1/transit/routes/lane")
                    .body(request)
                    .retrieve()
                    .body(TransitLaneResponse.class);
            if (res == null || res.status() == null || res.geometries() == null) {
                return TransitLaneResponse.unavailable();
            }
            return res;
        } catch (RestClientResponseException e) {
            log.warn("hub /v1/transit/routes/lane failed status={}",
                    e.getStatusCode().value());
            return TransitLaneResponse.unavailable();
        } catch (RuntimeException e) {
            // hub 다운·타임아웃·역직렬화 실패 등. 직선으로 물러서면 된다.
            log.warn("hub /v1/transit/routes/lane failed cause={}",
                    e.getClass().getSimpleName());
            return TransitLaneResponse.unavailable();
        }
    }

    /**
     * 응답 본문 InputStream 을 UTF-8 문자열로 안전하게 변환.
     *
     * stream 이 null 이거나 IOException 이면 빈 문자열을 반환한다. 진단
     * 요약 용도이므로 ERROR_BODY_MAX(4KB)까지만 읽어 과대 응답이 메모리에
     * 무제한 버퍼링되는 것을 막는다. try-with-resources 로 스트림을 닫는다.
     */
    private static String readBody(java.io.InputStream stream) {
        if (stream == null) {
            return "";
        }
        try (stream) {
            return new String(
                    stream.readNBytes(ERROR_BODY_MAX), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "";
        }
    }
}

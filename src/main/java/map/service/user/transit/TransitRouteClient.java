package map.service.user.transit;

import java.nio.charset.StandardCharsets;
import map.service.user.transit.dto.TransitRouteOptionsResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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

    private final RestClient client;

    /** 오류 응답 본문을 메모리에 읽을 최대 바이트(과대 응답 OOM 방지). */
    private static final int ERROR_BODY_MAX = 4096;

    public TransitRouteClient(@Qualifier("hubRestClient") RestClient client) {
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
                        .queryParam("start_lat", startLat)
                        .queryParam("start_lng", startLng)
                        .queryParam("end_lat", endLat)
                        .queryParam("end_lng", endLng)
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

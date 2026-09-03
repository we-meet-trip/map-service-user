package map.service.user.transit;

import java.nio.charset.StandardCharsets;
import map.service.user.transit.dto.SubwayRouteResponse;
import map.service.user.global.crypto.LocationSeal;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * SubwayRouteClient — hub /v1/transit/subway 호출 어댑터 (경계 B3)
 *
 * client 의 지하철 경로 요청을 hub 의 경로 조회 엔드포인트로 동기 전달한다.
 * 장소·리뷰·사진 조회와 같은 RestClient 빈을 재사용한다.
 *
 * BFF 는 결과를 캐시하지 않는다. hub 가 좌표 단위로 이미 담아 두고 하루
 * 호출 상한까지 관리하므로, 여기에 한 겹 더 두면 실시간성만 떨어진다.
 *
 * client: @Qualifier("hubRestClient") RestClient. base URL/타임아웃은
 *         HubClientConfig 가 구성한다.
 */
@Component
public class SubwayRouteClient {

    private final RestClient client;

    /** 오류 응답 본문을 메모리에 읽을 최대 바이트(과대 응답 OOM 방지). */
    private static final int ERROR_BODY_MAX = 4096;

    private final LocationSeal seal;

    public SubwayRouteClient(@Qualifier("hubRestClient") RestClient client,
                             LocationSeal seal) {
        this.seal = seal;
        this.client = client;
    }

    /**
     * hub GET /v1/transit/subway 호출.
     *
     * startLat / startLng: 출발 좌표. endLat / endLng: 도착 좌표.
     *
     * 외부 조회 실패는 예외가 아니라 응답의 status 로 온다. 그대로 흘려보내
     * 화면이 "경로 없음"과 "조회 불가"를 구분해 보여줄 수 있게 한다.
     */
    public SubwayRouteResponse fetch(
            double startLat, double startLng, double endLat, double endLng) {
        return client.get()
                .uri(uri -> uri.path("/v1/transit/subway")
                        .queryParam("loc",
                                seal.sealPair(startLat, startLng, endLat, endLng))
                        .build())
                .retrieve()
                .onStatus(
                        HttpStatusCode::isError,
                        (req, res) -> {
                            String body = readBody(res.getBody());
                            throw new SubwayRouteException(
                                    res.getStatusCode().value(), body);
                        })
                .body(SubwayRouteResponse.class);
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

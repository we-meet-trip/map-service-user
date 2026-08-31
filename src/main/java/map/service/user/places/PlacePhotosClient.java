package map.service.user.places;

import java.nio.charset.StandardCharsets;
import map.service.user.places.dto.PlacePhotosResponse;
import map.service.user.global.crypto.LocationSeal;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * PlacePhotosClient — hub /v1/places/photos 호출 어댑터 (경계 B3)
 *
 * client 의 장소 사진 요청을 hub 의 사진 조회 엔드포인트로 동기 전달한다.
 * 장소·리뷰 검색과 같은 RestClient 빈을 재사용한다.
 *
 * 사진 URL 은 수명이 짧아 BFF 가 캐시하지 않는다. 요청이 올 때마다 hub 로
 * 그대로 넘긴다.
 *
 * client: @Qualifier("hubRestClient") RestClient. base URL/타임아웃은
 *         HubClientConfig 가 구성한다.
 */
@Component
public class PlacePhotosClient {

    private final RestClient client;

    /** 오류 응답 본문을 메모리에 읽을 최대 바이트(과대 응답 OOM 방지). */
    private static final int ERROR_BODY_MAX = 4096;

    private final LocationSeal seal;

    public PlacePhotosClient(@Qualifier("hubRestClient") RestClient client,
                             LocationSeal seal) {
        this.seal = seal;
        this.client = client;
    }

    /**
     * hub GET /v1/places/photos 호출.
     *
     * query: 장소명(필수).
     * lat / lng: 장소 좌표(필수). 이름만 보내면 같은 상호의 다른 동네
     *            지점이 잡히므로 좌표를 함께 넘긴다.
     */
    public PlacePhotosResponse fetch(String query, double lat, double lng) {
        return client.get()
                .uri(uri -> uri.path("/v1/places/photos")
                        .queryParam("query", query)
                        .queryParam("loc", seal.seal(lat, lng))
                        .build())
                .retrieve()
                .onStatus(
                        HttpStatusCode::isError,
                        (req, res) -> {
                            String body = readBody(res.getBody());
                            throw new PlacePhotosException(
                                    res.getStatusCode().value(), body);
                        })
                .body(PlacePhotosResponse.class);
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

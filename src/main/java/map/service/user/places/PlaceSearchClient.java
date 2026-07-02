package map.service.user.places;

import java.nio.charset.StandardCharsets;
import map.service.user.places.dto.PlaceSearchResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * PlaceSearchClient — hub /v1/places 호출 어댑터 (경계 B3)
 *
 * client 의 장소 검색 요청을 hub 의 장소 조회 엔드포인트로 동기 전달한다.
 * 날씨 호출과 같은 RestClient 빈을 재사용한다.
 *
 * client: @Qualifier("hubRestClient") RestClient. base URL/타임아웃은
 *         HubClientConfig 가 구성한다.
 */
@Component
public class PlaceSearchClient {

    private final RestClient client;

    /** 오류 응답 본문을 메모리에 읽을 최대 바이트(과대 응답 OOM 방지). */
    private static final int ERROR_BODY_MAX = 4096;

    public PlaceSearchClient(@Qualifier("hubRestClient") RestClient client) {
        this.client = client;
    }

    /**
     * hub GET /v1/places 호출. 빈 값/누락 파라미터는 쿼리에서 생략한다.
     *
     * province: 광역시도(필수).
     * city: 시군구. 비어 있으면 생략.
     * keyword: 검색어. 비어 있으면 생략(hub 가 행정구역명을 검색어로 쓴다).
     * category: 카카오 카테고리 그룹 코드. 비어 있으면 생략.
     * mobility: 이동수단. 비어 있으면 생략(코스 전체).
     * size: 출처별 최대 결과 수. null 이면 hub 기본값.
     */
    public PlaceSearchResponse search(
            String province,
            String city,
            String keyword,
            String category,
            String mobility,
            Integer size
    ) {
        return client.get()
                .uri(uri -> {
                    uri.path("/v1/places").queryParam("province", province);
                    if (city != null && !city.isBlank()) {
                        uri.queryParam("city", city);
                    }
                    if (keyword != null && !keyword.isBlank()) {
                        uri.queryParam("keyword", keyword);
                    }
                    if (category != null && !category.isBlank()) {
                        uri.queryParam("category_group_code", category);
                    }
                    if (mobility != null && !mobility.isBlank()) {
                        uri.queryParam("mobility", mobility);
                    }
                    if (size != null) {
                        uri.queryParam("size", size);
                    }
                    return uri.build();
                })
                .retrieve()
                .onStatus(
                        HttpStatusCode::isError,
                        (req, res) -> {
                            String body = readBody(res.getBody());
                            throw new PlaceSearchException(
                                    res.getStatusCode().value(), body);
                        })
                .body(PlaceSearchResponse.class);
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

package map.service.user.places;

import java.nio.charset.StandardCharsets;
import map.service.user.places.dto.ReviewSearchResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * ReviewSearchClient — hub /v1/reviews 호출 어댑터 (경계 B3)
 *
 * client 의 리뷰 검색 요청을 hub 의 리뷰 조회 엔드포인트로 동기 전달한다.
 * 장소 검색과 같은 RestClient 빈을 재사용한다.
 *
 * client: @Qualifier("hubRestClient") RestClient. base URL/타임아웃은
 *         HubClientConfig 가 구성한다.
 */
@Component
public class ReviewSearchClient {

    private final RestClient client;

    /** 오류 응답 본문을 메모리에 읽을 최대 바이트(과대 응답 OOM 방지). */
    private static final int ERROR_BODY_MAX = 4096;

    public ReviewSearchClient(@Qualifier("hubRestClient") RestClient client) {
        this.client = client;
    }

    /**
     * hub GET /v1/reviews 호출. 빈 값/누락 파라미터는 쿼리에서 생략한다.
     *
     * query: 검색어(필수).
     * display: 결과 개수. null 이면 hub 기본값.
     * start: 조회 시작 위치. null 이면 첫 구간.
     * sort: 정렬 기준("sim" 정확도 · "date" 최신순). null 이면 hub 기본값.
     *       장소 상세의 블로그 목록은 최신순으로 보여주므로 이 값이 필요하다.
     */
    public ReviewSearchResponse search(
            String query, Integer display, Integer start, String sort
    ) {
        return client.get()
                .uri(uri -> {
                    uri.path("/v1/reviews").queryParam("query", query);
                    if (display != null) {
                        uri.queryParam("display", display);
                    }
                    if (start != null) {
                        uri.queryParam("start", start);
                    }
                    if (sort != null) {
                        uri.queryParam("sort", sort);
                    }
                    return uri.build();
                })
                .retrieve()
                .onStatus(
                        HttpStatusCode::isError,
                        (req, res) -> {
                            String body = readBody(res.getBody());
                            throw new ReviewSearchException(
                                    res.getStatusCode().value(), body);
                        })
                .body(ReviewSearchResponse.class);
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

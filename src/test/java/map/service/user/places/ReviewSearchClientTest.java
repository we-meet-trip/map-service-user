package map.service.user.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import map.service.user.places.dto.ReviewSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * ReviewSearchClientTest — hub /v1/reviews 호출 어댑터 단위 테스트
 *
 * 정상 응답 파싱과 hub 오류(5xx) → ReviewSearchException 변환을 검증한다.
 */
@DisplayName("ReviewSearchClient 단위 테스트")
class ReviewSearchClientTest {

    private MockRestServiceServer server;
    private ReviewSearchClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://hub:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ReviewSearchClient(builder.build());
    }

    @Test
    @DisplayName("정상 응답 — ReviewSearchResponse 파싱")
    void searchParsesResponse() {
        String json = """
                {"query":"cafe","reviews":[
                  {"title":"제목","description":"설명","bloggername":"블로거",
                   "postdate":"20260101","link":"http://example.com/1"}
                ],"count":1}""";
        server.expect(requestTo(startsWith("http://hub:8000/v1/reviews")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("query", "cafe"))
                .andExpect(queryParam("display", "5"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        ReviewSearchResponse response = client.search("cafe", 5);

        assertThat(response.query()).isEqualTo("cafe");
        assertThat(response.count()).isEqualTo(1);
        assertThat(response.reviews()).hasSize(1);
        assertThat(response.reviews().get(0).title()).isEqualTo("제목");
        assertThat(response.reviews().get(0).bloggername()).isEqualTo("블로거");
        server.verify();
    }

    @Test
    @DisplayName("display 미지정 — 쿼리에서 생략")
    void searchOmitsDisplayWhenNull() {
        server.expect(requestTo(startsWith("http://hub:8000/v1/reviews")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("query", "cafe"))
                .andRespond(withSuccess(
                        "{\"query\":\"cafe\",\"reviews\":[],\"count\":0}",
                        MediaType.APPLICATION_JSON));

        ReviewSearchResponse response = client.search("cafe", null);

        assertThat(response.count()).isZero();
        server.verify();
    }

    @Test
    @DisplayName("hub 5xx — ReviewSearchException 으로 변환")
    void searchThrowsOnUpstreamError() {
        server.expect(requestTo(startsWith("http://hub:8000/v1/reviews")))
                .andRespond(withServerError().body("upstream boom"));

        assertThatThrownBy(() -> client.search("cafe", 3))
                .isInstanceOf(ReviewSearchException.class)
                .satisfies(e -> {
                    ReviewSearchException ex = (ReviewSearchException) e;
                    assertThat(ex.statusCode()).isEqualTo(500);
                    assertThat(ex.body()).contains("upstream boom");
                });
    }
}

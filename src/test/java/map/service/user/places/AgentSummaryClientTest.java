package map.service.user.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;
import map.service.user.places.dto.ReviewItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * AgentSummaryClientTest — agent 요약 호출 어댑터 단위 테스트
 *
 * 가장 중요한 검증은 **배치 응답의 위치 되짚기**다. 근거가 없는 장소는
 * 보내기 전에 걸러지므로 응답의 위치는 '보낸 목록' 기준이고, 이를 호출자
 * 목록의 위치로 되돌리지 않으면 남의 요약이 남의 자리에 저장된다.
 */
@DisplayName("AgentSummaryClient 단위 테스트")
class AgentSummaryClientTest {

    private MockRestServiceServer server;
    private AgentSummaryClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder =
                RestClient.builder().baseUrl("http://agent:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AgentSummaryClient(builder.build());
    }

    private static ReviewItem review(String title, String description) {
        return new ReviewItem(title, description, "블로거", "20260101", "링크");
    }

    // ── 단건 ─────────────────────────────────────────────────

    @Test
    @DisplayName("정상 응답 — 두 줄을 그대로 돌려준다")
    void summarizeParsesBullets() {
        server.expect(requestTo("http://agent:8000/v1/reviews/summary"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.place_name").value("속초해변"))
                .andExpect(jsonPath("$.reviews[0].description").value("모래가 곱다"))
                .andRespond(withSuccess(
                        "{\"bullets\":[\"첫 줄\",\"둘째 줄\"]}",
                        MediaType.APPLICATION_JSON));

        List<String> out = client.summarize(
                "속초해변", List.of(review("제목", "모래가 곱다")));

        assertThat(out).containsExactly("첫 줄", "둘째 줄");
        server.verify();
    }

    @Test
    @DisplayName("본문 없는 후기만 있으면 호출하지 않는다")
    void summarizeSkipsWhenNoUsableReviews() {
        List<String> out = client.summarize(
                "속초해변", List.of(review("제목", "   ")));

        assertThat(out).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("제목·본문을 상한에 맞춰 잘라 보낸다")
    void summarizeClampsLongFields() {
        server.expect(requestTo("http://agent:8000/v1/reviews/summary"))
                .andExpect(jsonPath("$.reviews[0].title").value("가".repeat(200)))
                .andExpect(
                        jsonPath("$.reviews[0].description").value("나".repeat(500)))
                .andRespond(withSuccess(
                        "{\"bullets\":[]}", MediaType.APPLICATION_JSON));

        client.summarize(
                "속초해변",
                List.of(review("가".repeat(300), "나".repeat(700))));

        server.verify();
    }

    @Test
    @DisplayName("근거로 보내는 후기 수에 상한이 있다")
    void summarizeCapsSnippetCount() {
        server.expect(requestTo("http://agent:8000/v1/reviews/summary"))
                .andExpect(jsonPath("$.reviews.length()").value(7))
                .andRespond(withSuccess(
                        "{\"bullets\":[]}", MediaType.APPLICATION_JSON));

        client.summarize(
                "속초해변",
                java.util.stream.IntStream.range(0, 12)
                        .mapToObj(i -> review("제목" + i, "본문" + i))
                        .toList());

        server.verify();
    }

    @Test
    @DisplayName("한도 초과(429)는 빈 목록으로 흡수한다")
    void summarizeAbsorbsRateLimit() {
        server.expect(requestTo("http://agent:8000/v1/reviews/summary"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        List<String> out = client.summarize(
                "속초해변", List.of(review("제목", "본문")));

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("모델 실패(5xx)도 빈 목록으로 흡수한다")
    void summarizeAbsorbsServerError() {
        server.expect(requestTo("http://agent:8000/v1/reviews/summary"))
                .andRespond(withServerError());

        List<String> out = client.summarize(
                "속초해변", List.of(review("제목", "본문")));

        assertThat(out).isEmpty();
    }

    // ── 배치 ─────────────────────────────────────────────────

    @Test
    @DisplayName("배치 — 장소 여럿을 한 번에 보내고 위치로 되받는다")
    void summarizeBatchMapsByIndex() {
        server.expect(requestTo("http://agent:8000/v1/reviews/summary/batch"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.places.length()").value(2))
                .andExpect(jsonPath("$.places[0].place_name").value("가"))
                .andExpect(jsonPath("$.places[1].place_name").value("나"))
                .andRespond(withSuccess("""
                        {"results":[
                          {"index":0,"bullets":["가-1","가-2"]},
                          {"index":1,"bullets":["나-1","나-2"]}
                        ]}""", MediaType.APPLICATION_JSON));

        Map<Integer, List<String>> out = client.summarizeBatch(List.of(
                new AgentSummaryClient.BatchPlace(
                        "가", "해변", List.of(review("t", "본문"))),
                new AgentSummaryClient.BatchPlace(
                        "나", null, List.of(review("t", "본문")))));

        assertThat(out).containsExactly(
                Map.entry(0, List.of("가-1", "가-2")),
                Map.entry(1, List.of("나-1", "나-2")));
        server.verify();
    }

    @Test
    @DisplayName("배치 — 근거 없는 장소가 빠져도 호출자 위치로 되짚는다")
    void summarizeBatchTranslatesIndexWhenPlaceFiltered() {
        // 가운데 장소는 본문이 없어 보내지 않는다. 서버는 보낸 목록 기준
        // 0·1 로 답하므로, 되짚지 않으면 '다' 의 요약이 '나' 에 붙는다.
        server.expect(requestTo("http://agent:8000/v1/reviews/summary/batch"))
                .andExpect(jsonPath("$.places.length()").value(2))
                .andExpect(jsonPath("$.places[0].place_name").value("가"))
                .andExpect(jsonPath("$.places[1].place_name").value("다"))
                .andRespond(withSuccess("""
                        {"results":[
                          {"index":0,"bullets":["가-1","가-2"]},
                          {"index":1,"bullets":["다-1","다-2"]}
                        ]}""", MediaType.APPLICATION_JSON));

        Map<Integer, List<String>> out = client.summarizeBatch(List.of(
                new AgentSummaryClient.BatchPlace(
                        "가", null, List.of(review("t", "본문"))),
                new AgentSummaryClient.BatchPlace(
                        "나", null, List.of(review("t", "  "))),
                new AgentSummaryClient.BatchPlace(
                        "다", null, List.of(review("t", "본문")))));

        assertThat(out).containsOnlyKeys(0, 2);
        assertThat(out.get(0)).containsExactly("가-1", "가-2");
        assertThat(out.get(2)).containsExactly("다-1", "다-2");
        server.verify();
    }

    @Test
    @DisplayName("배치 — 스키마 상한을 넘는 장소는 보내지 않는다")
    void summarizeBatchCapsPlaceCount() {
        server.expect(requestTo("http://agent:8000/v1/reviews/summary/batch"))
                .andExpect(jsonPath("$.places.length()").value(7))
                .andRespond(withSuccess(
                        "{\"results\":[]}", MediaType.APPLICATION_JSON));

        client.summarizeBatch(java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> new AgentSummaryClient.BatchPlace(
                        "장소" + i, null, List.of(review("t", "본문"))))
                .toList());

        server.verify();
    }

    @Test
    @DisplayName("배치 — 범위 밖 위치로 답하면 그 항목을 버린다")
    void summarizeBatchDiscardsOutOfRangeIndex() {
        server.expect(requestTo("http://agent:8000/v1/reviews/summary/batch"))
                .andRespond(withSuccess("""
                        {"results":[
                          {"index":0,"bullets":["가-1","가-2"]},
                          {"index":9,"bullets":["없음-1","없음-2"]}
                        ]}""", MediaType.APPLICATION_JSON));

        Map<Integer, List<String>> out = client.summarizeBatch(List.of(
                new AgentSummaryClient.BatchPlace(
                        "가", null, List.of(review("t", "본문")))));

        assertThat(out).containsOnlyKeys(0);
    }

    @Test
    @DisplayName("배치 — 보낼 장소가 하나도 없으면 호출하지 않는다")
    void summarizeBatchSkipsWhenNothingToSend() {
        Map<Integer, List<String>> out = client.summarizeBatch(List.of(
                new AgentSummaryClient.BatchPlace(
                        "가", null, List.of(review("t", "   ")))));

        assertThat(out).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("배치 — 호출 실패는 빈 결과로 흡수한다")
    void summarizeBatchAbsorbsFailure() {
        server.expect(requestTo("http://agent:8000/v1/reviews/summary/batch"))
                .andRespond(withServerError());

        Map<Integer, List<String>> out = client.summarizeBatch(List.of(
                new AgentSummaryClient.BatchPlace(
                        "가", null, List.of(review("t", "본문")))));

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("배치 — 분류는 실어 보내고 단건에는 넣지 않는다")
    void summarizeBatchSendsCategory() {
        server.expect(requestTo("http://agent:8000/v1/reviews/summary/batch"))
                .andExpect(jsonPath("$.places[0].category").value("해변"))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("\"category\"")))
                .andRespond(withSuccess(
                        "{\"results\":[]}", MediaType.APPLICATION_JSON));

        client.summarizeBatch(List.of(new AgentSummaryClient.BatchPlace(
                "가", "해변", List.of(review("t", "본문")))));

        server.verify();
    }
}

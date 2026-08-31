package map.service.user.trip;

import map.service.user.global.crypto.TestLocationSeals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.ArrayList;
import java.util.List;
import map.service.user.trip.dto.HubDirectionsDtos.LegReq;
import map.service.user.trip.dto.HubDirectionsDtos.Point;
import map.service.user.trip.dto.HubDirectionsDtos.Route;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * HubDirectionsClientTest — hub /v1/directions/batch 호출 어댑터 단위 테스트
 *
 * 정상 응답 파싱(null 원소 혼재 포함), hub 5xx → null(best-effort 폴백), 그리고
 * 구간이 hub 상한을 넘을 때의 분할 전송(순서 보존·부분 실패 격리)을 검증한다.
 * ReviewSearchClientTest 의 MockRestServiceServer 패턴을 따른다.
 */
@DisplayName("HubDirectionsClient 단위 테스트")
class HubDirectionsClientTest {

    /** hub 요청 본문이 받는 구간 수 상한. 어댑터의 분할 단위와 같아야 한다. */
    private static final int MAX_LEGS_PER_BATCH = 20;

    private MockRestServiceServer server;
    private HubDirectionsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://hub:8000");
        server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(false)
                .build();
        client = new HubDirectionsClient(builder.build(), TestLocationSeals.enabled());
    }

    private static List<LegReq> twoLegs() {
        return List.of(
                new LegReq(new Point(37.57, 126.97), new Point(37.58, 126.98),
                        "A", "B"),
                new LegReq(new Point(37.58, 126.98), new Point(37.59, 126.99),
                        "B", "C"));
    }

    /** 구간 n 개. 좌표는 인덱스로 벌려 두어 어느 구간인지 구분된다. */
    private static List<LegReq> legs(int n) {
        List<LegReq> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new LegReq(
                    new Point(37.50 + i * 0.001, 126.90 + i * 0.001),
                    new Point(37.50 + (i + 1) * 0.001, 126.90 + (i + 1) * 0.001),
                    "P" + i, "P" + (i + 1)));
        }
        return out;
    }

    /**
     * 구간 n 개짜리 응답 본문. distance_m 을 offset+i 로 넣어 어느 요청의 몇 번째
     * 결과인지 되짚을 수 있게 한다.
     */
    private static String routesJson(int n, int offset) {
        StringBuilder sb = new StringBuilder("{\"routes\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"path\":[[37.5,126.9],[37.6,127.0]],\"distance_m\":")
                    .append(offset + i)
                    .append(",\"duration_s\":10}");
        }
        return sb.append("]}").toString();
    }

    @Test
    @DisplayName("정상 응답 — routes 파싱(null 원소 혼재)")
    void parsesRoutesWithNullElement() {
        String json = """
                {"routes":[
                  {"path":[[37.57,126.97],[37.58,126.98]],
                   "distance_m":1420,"duration_s":1230},
                  null
                ]}""";
        server.expect(requestTo(startsWith("http://hub:8000/v1/directions/batch")))
                .andExpect(method(HttpMethod.POST))
                // 본문에 좌표와 방문지 이름이 값 그대로 실리면 안 된다.
                // 감싸기가 이 호출부에서 빠져도 응답은 똑같이 오므로
                // 화면으로는 알 수 없다.
                .andExpect(request -> assertThat(
                        new String(((org.springframework.mock.http.client.MockClientHttpRequest) request)
                                .getBodyAsBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        .contains("\"loc\":\"v1.")
                        .doesNotContain("\"lat\"", "\"lng\""))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<Route> routes = client.fetchRoutes("walk", twoLegs());

        assertThat(routes).hasSize(2);
        Route r0 = routes.get(0);
        assertThat(r0).isNotNull();
        assertThat(r0.distanceM()).isEqualTo(1420);
        assertThat(r0.durationS()).isEqualTo(1230);
        assertThat(r0.path()).hasSize(2);
        assertThat(r0.path().get(0)).containsExactly(37.57, 126.97);
        assertThat(routes.get(1)).isNull();
        server.verify();
    }

    @Test
    @DisplayName("hub 5xx — null 반환(예외 미전파, 전 구간 직선 폴백)")
    void returnsNullOnUpstreamError() {
        server.expect(requestTo(startsWith("http://hub:8000/v1/directions/batch")))
                .andRespond(withServerError().body("boom"));

        List<Route> routes = client.fetchRoutes("walk", twoLegs());

        assertThat(routes).isNull();
    }

    @Test
    @DisplayName("상한과 같은 구간 수 — 요청 1회")
    void sendsSingleRequestAtLimit() {
        server.expect(ExpectedCount.once(),
                        requestTo(startsWith("http://hub:8000/v1/directions/batch")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(routesJson(MAX_LEGS_PER_BATCH, 0),
                        MediaType.APPLICATION_JSON));

        List<Route> routes = client.fetchRoutes("walk", legs(MAX_LEGS_PER_BATCH));

        assertThat(routes).hasSize(MAX_LEGS_PER_BATCH);
        assertThat(routes).doesNotContainNull();
        server.verify();
    }

    @Test
    @DisplayName("상한 + 1 구간 — 20/1 로 나눠 보내고 순서대로 이어 붙인다")
    void splitsJustOverLimit() {
        server.expect(ExpectedCount.once(),
                        requestTo(startsWith("http://hub:8000/v1/directions/batch")))
                .andRespond(withSuccess(routesJson(MAX_LEGS_PER_BATCH, 0),
                        MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(),
                        requestTo(startsWith("http://hub:8000/v1/directions/batch")))
                .andRespond(withSuccess(routesJson(1, MAX_LEGS_PER_BATCH),
                        MediaType.APPLICATION_JSON));

        List<Route> routes = client.fetchRoutes("walk", legs(MAX_LEGS_PER_BATCH + 1));

        assertThat(routes).hasSize(MAX_LEGS_PER_BATCH + 1);
        for (int i = 0; i <= MAX_LEGS_PER_BATCH; i++) {
            assertThat(routes.get(i)).isNotNull();
            assertThat(routes.get(i).distanceM()).isEqualTo(i);
        }
        server.verify();
    }

    @Test
    @DisplayName("40 구간 — 요청 2회, 인덱스가 원래 구간 번호와 맞는다")
    void splitsFortyLegsInOrder() {
        server.expect(ExpectedCount.once(),
                        requestTo(startsWith("http://hub:8000/v1/directions/batch")))
                .andRespond(withSuccess(routesJson(20, 0), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(),
                        requestTo(startsWith("http://hub:8000/v1/directions/batch")))
                .andRespond(withSuccess(routesJson(20, 20), MediaType.APPLICATION_JSON));

        List<Route> routes = client.fetchRoutes("bicycle", legs(40));

        assertThat(routes).hasSize(40);
        for (int i = 0; i < 40; i++) {
            assertThat(routes.get(i).distanceM()).isEqualTo(i);
        }
        server.verify();
    }

    @Test
    @DisplayName("한 요청만 실패 — 그 구간만 null, 나머지는 살아남는다")
    void isolatesFailedBatch() {
        server.expect(ExpectedCount.once(),
                        requestTo(startsWith("http://hub:8000/v1/directions/batch")))
                .andRespond(withSuccess(routesJson(20, 0), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(),
                        requestTo(startsWith("http://hub:8000/v1/directions/batch")))
                .andRespond(withServerError().body("boom"));
        server.expect(ExpectedCount.once(),
                        requestTo(startsWith("http://hub:8000/v1/directions/batch")))
                .andRespond(withSuccess(routesJson(5, 40), MediaType.APPLICATION_JSON));

        List<Route> routes = client.fetchRoutes("walk", legs(45));

        assertThat(routes).hasSize(45);
        for (int i = 0; i < 20; i++) {
            assertThat(routes.get(i)).as("첫 요청 구간 %d", i).isNotNull();
        }
        for (int i = 20; i < 40; i++) {
            assertThat(routes.get(i)).as("실패한 요청 구간 %d", i).isNull();
        }
        for (int i = 40; i < 45; i++) {
            assertThat(routes.get(i)).as("마지막 요청 구간 %d", i).isNotNull();
            assertThat(routes.get(i).distanceM()).isEqualTo(i);
        }
        server.verify();
    }

    @Test
    @DisplayName("모든 요청 실패 — null(전 구간 직선 폴백)")
    void returnsNullWhenEveryBatchFails() {
        server.expect(ExpectedCount.twice(),
                        requestTo(startsWith("http://hub:8000/v1/directions/batch")))
                .andRespond(withServerError().body("boom"));

        List<Route> routes = client.fetchRoutes("walk", legs(25));

        assertThat(routes).isNull();
        server.verify();
    }

    @Test
    @DisplayName("응답이 요청보다 짧아도 뒤 요청의 인덱스가 밀리지 않는다")
    void padsShortResponse() {
        server.expect(ExpectedCount.once(),
                        requestTo(startsWith("http://hub:8000/v1/directions/batch")))
                .andRespond(withSuccess(routesJson(3, 0), MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(),
                        requestTo(startsWith("http://hub:8000/v1/directions/batch")))
                .andRespond(withSuccess(routesJson(2, 20), MediaType.APPLICATION_JSON));

        List<Route> routes = client.fetchRoutes("walk", legs(22));

        assertThat(routes).hasSize(22);
        assertThat(routes.get(2).distanceM()).isEqualTo(2);
        assertThat(routes.get(3)).isNull();
        assertThat(routes.get(19)).isNull();
        assertThat(routes.get(20).distanceM()).isEqualTo(20);
        assertThat(routes.get(21).distanceM()).isEqualTo(21);
        server.verify();
    }
}

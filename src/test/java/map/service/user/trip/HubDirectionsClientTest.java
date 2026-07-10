package map.service.user.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import map.service.user.trip.dto.HubDirectionsDtos.LegReq;
import map.service.user.trip.dto.HubDirectionsDtos.Point;
import map.service.user.trip.dto.HubDirectionsDtos.Route;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * HubDirectionsClientTest — hub /v1/directions/batch 호출 어댑터 단위 테스트
 *
 * 정상 응답 파싱(null 원소 혼재 포함)과 hub 5xx → null(best-effort 폴백)을
 * 검증한다. ReviewSearchClientTest 의 MockRestServiceServer 패턴을 따른다.
 */
@DisplayName("HubDirectionsClient 단위 테스트")
class HubDirectionsClientTest {

    private MockRestServiceServer server;
    private HubDirectionsClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://hub:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HubDirectionsClient(builder.build());
    }

    private static List<LegReq> twoLegs() {
        return List.of(
                new LegReq(new Point(37.57, 126.97), new Point(37.58, 126.98),
                        "A", "B"),
                new LegReq(new Point(37.58, 126.98), new Point(37.59, 126.99),
                        "B", "C"));
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
}

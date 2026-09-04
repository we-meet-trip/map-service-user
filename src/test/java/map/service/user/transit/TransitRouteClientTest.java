package map.service.user.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import map.service.user.global.crypto.TestLocationSeals;
import map.service.user.transit.dto.TransitRouteOptionsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * TransitRouteClientTest — hub /v1/transit/routes 호출 어댑터 단위 테스트
 *
 * SubwayRouteClientTest 와 같은 검증 축이되, 경로 후보가 여러 건 오는 것과
 * geometry/modes 필드가 그대로 파싱되는지를 추가로 본다.
 */
@DisplayName("TransitRouteClient 단위 테스트")
class TransitRouteClientTest {

    private MockRestServiceServer server;
    private TransitRouteClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://hub:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new TransitRouteClient(builder.build(), TestLocationSeals.enabled());
    }

    @Test
    @DisplayName("정상 응답 — 경로 후보 목록 파싱 및 좌표 네 개 전달")
    void fetchParsesRoutes() {
        String json = """
                {"status":"ok","routes":[
                  {"total_time_min":28,"fare":1650,
                   "transfer_count":2,"total_walk_m":903,
                   "modes":["subway"],
                   "legs":[
                     {"type":"walk","start_name":"","end_name":"",
                      "section_time_min":11,"geometry":[]},
                     {"type":"subway","line_name":"수도권 9호선",
                      "start_name":"언주","end_name":"신논현",
                      "section_time_min":2,"station_count":1,
                      "geometry":[[37.507323,127.033909],[37.504454,127.024504]]}
                   ]},
                  {"total_time_min":44,"fare":1750,
                   "transfer_count":2,"total_walk_m":314,
                   "modes":["subway","bus"],
                   "legs":[
                     {"type":"bus","start_name":"신림동별빛거리입구",
                      "end_name":"여의도역3번출구",
                      "section_time_min":20,"station_count":11,
                      "geometry":[[37.486038,126.92959],[37.49057,126.927592]]}
                   ]}
                ]}""";
        server.expect(requestTo(startsWith("http://hub:8000/v1/transit/routes")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(TestLocationSeals.coordinatesAreSealed())
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        TransitRouteOptionsResponse response =
                client.fetch(37.4979, 127.0276, 37.5663, 126.9779, "all");

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.routes()).hasSize(2);
        assertThat(response.routes().get(0).modes()).containsExactly("subway");
        assertThat(response.routes().get(0).legs().get(0).geometry()).isEmpty();
        assertThat(response.routes().get(0).legs().get(1).geometry())
                .containsExactly(java.util.List.of(37.507323, 127.033909),
                        java.util.List.of(37.504454, 127.024504));
        assertThat(response.routes().get(1).modes())
                .containsExactly("subway", "bus");
        assertThat(response.routes().get(1).legs().get(0).type()).isEqualTo("bus");
    }

    @Test
    @DisplayName("경로 없음 — 오류가 아니라 status 로 전달된다")
    void fetchPassesNotFoundThrough() {
        server.expect(requestTo(startsWith("http://hub:8000/v1/transit/routes")))
                .andRespond(withSuccess(
                        "{\"status\":\"not_found\",\"routes\":[]}",
                        MediaType.APPLICATION_JSON));

        TransitRouteOptionsResponse response =
                client.fetch(37.4979, 127.0276, 33.5, 126.5, "all");

        assertThat(response.status()).isEqualTo("not_found");
        assertThat(response.routes()).isEmpty();
    }

    @Test
    @DisplayName("조회 불가 — 경로 없음과 다른 값으로 전달된다")
    void fetchPassesUnavailableThrough() {
        server.expect(requestTo(startsWith("http://hub:8000/v1/transit/routes")))
                .andRespond(withSuccess(
                        "{\"status\":\"unavailable\",\"routes\":[]}",
                        MediaType.APPLICATION_JSON));

        TransitRouteOptionsResponse response =
                client.fetch(37.4979, 127.0276, 37.5663, 126.9779, "all");

        assertThat(response.status()).isEqualTo("unavailable");
        assertThat(response.status()).isNotEqualTo("not_found");
    }

    @Test
    @DisplayName("hub 5xx — TransitRouteException 으로 변환된다")
    void fetchWrapsUpstreamError() {
        server.expect(requestTo(startsWith("http://hub:8000/v1/transit/routes")))
                .andRespond(withServerError().body("hub down"));

        assertThatThrownBy(
                () -> client.fetch(37.4979, 127.0276, 37.5663, 126.9779, "all"))
                .isInstanceOf(TransitRouteException.class)
                .satisfies(e -> assertThat(
                        ((TransitRouteException) e).statusCode()).isEqualTo(500));
    }
}

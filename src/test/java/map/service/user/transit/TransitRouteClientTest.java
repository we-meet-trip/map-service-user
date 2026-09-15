package map.service.user.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import map.service.user.global.crypto.TestLocationSeals;
import java.util.List;
import map.service.user.transit.dto.TransitLaneRequest;
import map.service.user.transit.dto.TransitLaneResponse;
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
    @DisplayName("mapObj — 경로 후보 단위 값이 그대로 전달되고, 없는 후보는 null")
    void fetchParsesMapObj() {
        String json = """
                {"status":"ok","routes":[
                  {"total_time_min":28,"fare":1650,
                   "transfer_count":2,"total_walk_m":903,
                   "modes":["subway"],
                   "map_obj":"18:2:132:136@204:2:917:915",
                   "legs":[
                     {"type":"subway","start_name":"언주","end_name":"신논현",
                      "section_time_min":2,"geometry":[[37.507323,127.033909]]}
                   ]},
                  {"total_time_min":190,"fare":23000,
                   "transfer_count":0,"total_walk_m":300,
                   "modes":["intercity"],
                   "legs":[
                     {"type":"intercity","start_name":"동서울","end_name":"속초",
                      "section_time_min":180,"geometry":[]}
                   ]}
                ]}""";
        server.expect(requestTo(startsWith("http://hub:8000/v1/transit/routes")))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        TransitRouteOptionsResponse response =
                client.fetch(37.4979, 127.0276, 37.5663, 126.9779, "all");

        assertThat(response.routes().get(0).mapObj())
                .isEqualTo("18:2:132:136@204:2:917:915");
        // 발급처가 주지 않는 후보(시외버스 등)는 필드가 없어 null.
        assertThat(response.routes().get(1).mapObj()).isNull();
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

    // ── fetchLane (POST /v1/transit/routes/lane) ──────────────────────

    private static final TransitLaneRequest LANE_REQUEST = new TransitLaneRequest(
            "18:2:132:136@204:2:917:915", List.of("walk", "subway", "walk"));

    @Test
    @DisplayName("노선 좌표 — mapObj·types 를 본문으로 보내고 geometries 를 그대로 받는다")
    void fetchLaneSendsBodyAndParses() {
        server.expect(requestTo("http://hub:8000/v1/transit/routes/lane"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"map_obj":"18:2:132:136@204:2:917:915",
                         "types":["walk","subway","walk"]}"""))
                .andRespond(withSuccess("""
                        {"status":"ok",
                         "geometries":[[],[[37.5663,126.9779],[37.5219,126.9243]],[]]}""",
                        MediaType.APPLICATION_JSON));

        TransitLaneResponse res = client.fetchLane(LANE_REQUEST);

        assertThat(res.status()).isEqualTo("ok");
        assertThat(res.geometries()).hasSize(3);
        assertThat(res.geometries().get(0)).isEmpty();
        assertThat(res.geometries().get(1).get(0)).containsExactly(37.5663, 126.9779);
    }

    @Test
    @DisplayName("노선 좌표 — hub 5xx 는 예외가 아니라 unavailable(기존 직선 유지)")
    void fetchLaneHubErrorIsUnavailable() {
        server.expect(requestTo("http://hub:8000/v1/transit/routes/lane"))
                .andRespond(withServerError().body("hub down"));

        TransitLaneResponse res = client.fetchLane(LANE_REQUEST);

        assertThat(res.status()).isEqualTo("unavailable");
        assertThat(res.geometries()).isEmpty();
    }

    @Test
    @DisplayName("노선 좌표 — 본문이 비거나 모양이 틀려도 unavailable")
    void fetchLaneMalformedBodyIsUnavailable() {
        server.expect(requestTo("http://hub:8000/v1/transit/routes/lane"))
                .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));

        TransitLaneResponse res = client.fetchLane(LANE_REQUEST);

        assertThat(res.status()).isEqualTo("unavailable");
        assertThat(res.geometries()).isEmpty();
    }
}

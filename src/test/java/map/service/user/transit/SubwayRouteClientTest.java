package map.service.user.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import map.service.user.transit.dto.SubwayRouteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * SubwayRouteClientTest — hub /v1/transit/subway 호출 어댑터 단위 테스트
 *
 * hub 의 snake_case 응답이 record 로 파싱되는지, 좌표 네 개가 쿼리에 실리는지,
 * 조회 실패(status)와 hub 오류(5xx)가 서로 다른 경로로 다뤄지는지 검증한다.
 */
@DisplayName("SubwayRouteClient 단위 테스트")
class SubwayRouteClientTest {

    private MockRestServiceServer server;
    private SubwayRouteClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://hub:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SubwayRouteClient(builder.build());
    }

    @Test
    @DisplayName("정상 응답 — 경로 파싱 및 좌표 네 개 전달")
    void fetchParsesRoute() {
        String json = """
                {"status":"ok","route":{
                  "total_time_min":42,"fare":1500,
                  "transfer_count":1,"total_walk_m":620,
                  "steps":[
                    {"type":"walk","start_name":"출발지","end_name":"강남",
                     "section_time_min":4},
                    {"type":"subway","line_name":"수도권 2호선",
                     "start_name":"강남","end_name":"시청",
                     "section_time_min":34,"station_count":11}
                  ]}}""";
        server.expect(requestTo(startsWith("http://hub:8000/v1/transit/subway")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("start_lat", "37.4979"))
                .andExpect(queryParam("start_lng", "127.0276"))
                .andExpect(queryParam("end_lat", "37.5663"))
                .andExpect(queryParam("end_lng", "126.9779"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        SubwayRouteResponse response =
                client.fetch(37.4979, 127.0276, 37.5663, 126.9779);

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.route().totalTimeMin()).isEqualTo(42);
        assertThat(response.route().fare()).isEqualTo(1500);
        assertThat(response.route().transferCount()).isEqualTo(1);
        assertThat(response.route().totalWalkM()).isEqualTo(620);
        assertThat(response.route().steps()).hasSize(2);
        assertThat(response.route().steps().get(0).type()).isEqualTo("walk");
        // 걷는 구간에는 노선명도 역 수도 없다.
        assertThat(response.route().steps().get(0).lineName()).isNull();
        assertThat(response.route().steps().get(0).stationCount()).isNull();
        assertThat(response.route().steps().get(1).lineName())
                .isEqualTo("수도권 2호선");
        assertThat(response.route().steps().get(1).stationCount()).isEqualTo(11);
    }

    @Test
    @DisplayName("경로 없음 — 오류가 아니라 status 로 전달된다")
    void fetchPassesNotFoundThrough() {
        server.expect(requestTo(startsWith("http://hub:8000/v1/transit/subway")))
                .andRespond(withSuccess(
                        "{\"status\":\"not_found\",\"route\":null}",
                        MediaType.APPLICATION_JSON));

        SubwayRouteResponse response =
                client.fetch(37.4979, 127.0276, 33.5, 126.5);

        assertThat(response.status()).isEqualTo("not_found");
        assertThat(response.route()).isNull();
    }

    @Test
    @DisplayName("조회 불가 — 경로 없음과 다른 값으로 전달된다")
    void fetchPassesUnavailableThrough() {
        server.expect(requestTo(startsWith("http://hub:8000/v1/transit/subway")))
                .andRespond(withSuccess(
                        "{\"status\":\"unavailable\",\"route\":null}",
                        MediaType.APPLICATION_JSON));

        SubwayRouteResponse response =
                client.fetch(37.4979, 127.0276, 37.5663, 126.9779);

        // 둘을 합치면 외부 장애가 "갈 수 있는 길이 없다"로 표시된다.
        assertThat(response.status()).isEqualTo("unavailable");
        assertThat(response.status()).isNotEqualTo("not_found");
    }

    @Test
    @DisplayName("hub 5xx — SubwayRouteException 으로 변환된다")
    void fetchWrapsUpstreamError() {
        server.expect(requestTo(startsWith("http://hub:8000/v1/transit/subway")))
                .andRespond(withServerError().body("hub down"));

        assertThatThrownBy(
                () -> client.fetch(37.4979, 127.0276, 37.5663, 126.9779))
                .isInstanceOf(SubwayRouteException.class)
                .satisfies(e -> assertThat(
                        ((SubwayRouteException) e).statusCode()).isEqualTo(500));
    }
}

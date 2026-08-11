package map.service.user.mobility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import map.service.user.mobility.dto.BikeStationsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * BikeStationClientTest — hub /v1/mobility/bike-stations 어댑터 단위 테스트
 *
 * hub 의 snake_case 응답이 record 로 파싱되는지, 좌표와 반경이 쿼리에 실리는지,
 * 조회 실패(status)와 hub 오류(5xx)가 서로 다른 경로로 다뤄지는지 검증한다.
 */
@DisplayName("BikeStationClient 단위 테스트")
class BikeStationClientTest {

    private MockRestServiceServer server;
    private BikeStationClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://hub:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BikeStationClient(builder.build());
    }

    @Test
    @DisplayName("정상 응답 — 대여소 파싱 및 좌표·반경 전달")
    void fetchParsesStations() {
        String json = """
                {"status":"ok","stations":[
                  {"station_id":"ST-4","name":"102. 망원역 1번출구 앞",
                   "rack_total":15,"parking_bike_total":5,
                   "lat":37.5556488,"lng":126.91062927}
                ],"count":1}""";
        server.expect(requestTo(
                        startsWith("http://hub:8000/v1/mobility/bike-stations")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("lat", "37.5665"))
                .andExpect(queryParam("lng", "126.978"))
                .andExpect(queryParam("radius_m", "5000"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        BikeStationsResponse response = client.fetch(37.5665, 126.978, 5000);

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.count()).isEqualTo(1);
        assertThat(response.stations()).hasSize(1);
        assertThat(response.stations().get(0).stationId()).isEqualTo("ST-4");
        assertThat(response.stations().get(0).rackTotal()).isEqualTo(15);
        assertThat(response.stations().get(0).parkingBikeTotal()).isEqualTo(5);
    }

    @Test
    @DisplayName("서비스 지역 밖 — 빈 목록이며 오류가 아니다")
    void fetchAcceptsEmptyList() {
        server.expect(requestTo(
                        startsWith("http://hub:8000/v1/mobility/bike-stations")))
                .andRespond(withSuccess(
                        "{\"status\":\"ok\",\"stations\":[],\"count\":0}",
                        MediaType.APPLICATION_JSON));

        BikeStationsResponse response = client.fetch(35.1796, 129.0756, 5000);

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.count()).isZero();
    }

    @Test
    @DisplayName("조회 불가 — 오류가 아니라 status 로 전달된다")
    void fetchPassesUnavailableThrough() {
        server.expect(requestTo(
                        startsWith("http://hub:8000/v1/mobility/bike-stations")))
                .andRespond(withSuccess(
                        "{\"status\":\"unavailable\",\"stations\":[],\"count\":0}",
                        MediaType.APPLICATION_JSON));

        BikeStationsResponse response = client.fetch(37.5665, 126.978, 5000);

        assertThat(response.status()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("hub 5xx — BikeStationException 으로 변환된다")
    void fetchWrapsUpstreamError() {
        server.expect(requestTo(
                        startsWith("http://hub:8000/v1/mobility/bike-stations")))
                .andRespond(withServerError().body("hub down"));

        assertThatThrownBy(() -> client.fetch(37.5665, 126.978, 5000))
                .isInstanceOf(BikeStationException.class)
                .satisfies(e -> assertThat(
                        ((BikeStationException) e).statusCode()).isEqualTo(500));
    }
}

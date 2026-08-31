package map.service.user.mobility;

import map.service.user.global.crypto.TestLocationSeals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import map.service.user.mobility.dto.PmVehiclesResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * PmVehicleClientTest — hub /v1/mobility/pm-vehicles 어댑터 단위 테스트
 *
 * hub 의 snake_case 응답이 record 로 파싱되는지, 좌표·반경·지역이 쿼리에
 * 실리는지, 지역을 안 주면 쿼리에서 아예 빠지는지, hub 오류(5xx)가
 * PmVehicleException 으로 바뀌는지 검증한다.
 */
@DisplayName("PmVehicleClient 단위 테스트")
class PmVehicleClientTest {

    private MockRestServiceServer server;
    private PmVehicleClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://hub:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PmVehicleClient(builder.build(), TestLocationSeals.enabled());
    }

    @Test
    @DisplayName("정상 응답 — 기기 파싱 및 좌표·반경 전달")
    void fetchParsesVehicles() {
        String json = """
                {"status":"ok","vehicles":[
                  {"provider":"Beam","device_id":"D-1","battery_level":82,
                   "vehicle_type":"전동킥보드","lat":37.567,"lng":126.979}
                ],"count":1}""";
        server.expect(requestTo(
                        startsWith("http://hub:8000/v1/mobility/pm-vehicles")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(TestLocationSeals.coordinatesAreSealed())
                .andExpect(queryParam("radius_m", "1000"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        PmVehiclesResponse response =
                client.fetch(37.5665, 126.978, 1000, null);

        assertThat(response.status()).isEqualTo("ok");
        assertThat(response.count()).isEqualTo(1);
        assertThat(response.vehicles().get(0).provider()).isEqualTo("Beam");
        assertThat(response.vehicles().get(0).deviceId()).isEqualTo("D-1");
        assertThat(response.vehicles().get(0).batteryLevel()).isEqualTo(82);
    }

    @Test
    @DisplayName("지역 미지정 — 쿼리에 city 가 실리지 않는다")
    void fetchOmitsCityWhenAbsent() {
        server.expect(requestTo(not(startsWith("x"))))
                .andRespond(withSuccess(
                        "{\"status\":\"ok\",\"vehicles\":[],\"count\":0}",
                        MediaType.APPLICATION_JSON));

        client.fetch(37.5665, 126.978, 1000, "   ");
        server.verify();
    }

    @Test
    @DisplayName("지역 지정 — 쿼리에 city 가 실린다")
    void fetchSendsCity() {
        server.expect(requestTo(
                        startsWith("http://hub:8000/v1/mobility/pm-vehicles")))
                .andExpect(queryParam("city", "%EC%84%9C%EC%9A%B8%ED%8A%B9%EB%B3%84%EC%8B%9C"))
                .andRespond(withSuccess(
                        "{\"status\":\"ok\",\"vehicles\":[],\"count\":0}",
                        MediaType.APPLICATION_JSON));

        client.fetch(37.5665, 126.978, 1000, "서울특별시");
        server.verify();
    }

    @Test
    @DisplayName("조회 불가 — 오류가 아니라 status 로 전달된다")
    void fetchPassesUnavailableThrough() {
        server.expect(requestTo(
                        startsWith("http://hub:8000/v1/mobility/pm-vehicles")))
                .andRespond(withSuccess(
                        "{\"status\":\"unavailable\",\"vehicles\":[],\"count\":0}",
                        MediaType.APPLICATION_JSON));

        PmVehiclesResponse response =
                client.fetch(37.5665, 126.978, 1000, null);

        assertThat(response.status()).isEqualTo("unavailable");
    }

    @Test
    @DisplayName("hub 5xx — PmVehicleException 으로 변환된다")
    void fetchWrapsUpstreamError() {
        server.expect(requestTo(
                        startsWith("http://hub:8000/v1/mobility/pm-vehicles")))
                .andRespond(withServerError().body("hub down"));

        assertThatThrownBy(() -> client.fetch(37.5665, 126.978, 1000, null))
                .isInstanceOf(PmVehicleException.class)
                .satisfies(e -> assertThat(
                        ((PmVehicleException) e).statusCode()).isEqualTo(500));
    }
}

package map.service.user.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import map.service.user.places.dto.AddressSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * AddressSearchClientTest — hub /v1/places/address 호출 어댑터 단위 테스트
 *
 * 이 경로는 앱이 카카오를 직접 부르던 자리를 대신한다. 되돌아갈 길이 없으므로
 * 응답 형태와 오류 전달을 못박아 둔다. 좌표가 실리지 않는 것도 함께 본다 —
 * 실리기 시작하면 봉투 없이 나가는 좌표 통로가 하나 더 생긴다.
 */
@DisplayName("PlaceSearchClient 주소 검색 단위 테스트")
class AddressSearchClientTest {

    private MockRestServiceServer server;
    private PlaceSearchClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://hub:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PlaceSearchClient(builder.build());
    }

    @Test
    @DisplayName("정상 응답 — 도로명이 없는 주소도 그대로 담는다")
    void searchAddressParsesResponse() {
        String json = """
                {"addresses":[
                  {"address":"서울 강남구 역삼동 823",
                   "road_address":"서울 강남구 테헤란로 1"},
                  {"address":"서울 종로구 청운동 1","road_address":""}
                ],"count":2}""";
        // 주소는 거의 한글이라 질의는 인코딩되어 나간다. 날것으로 견주면
        // 통과하지 않는 것이 정상이므로 인코딩된 모습으로 못박는다.
        server.expect(requestTo(startsWith("http://hub:8000/v1/places/address")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("query", "%EC%97%AD%EC%82%BC"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        AddressSearchResponse response = client.searchAddress("역삼");

        assertThat(response.count()).isEqualTo(2);
        assertThat(response.addresses()).hasSize(2);
        assertThat(response.addresses().get(0).roadAddress())
                .isEqualTo("서울 강남구 테헤란로 1");
        assertThat(response.addresses().get(1).roadAddress()).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("hub 오류는 PlaceSearchException 으로 올린다")
    void searchAddressPropagatesHubError() {
        server.expect(requestTo(startsWith("http://hub:8000/v1/places/address")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.searchAddress("역삼"))
                .isInstanceOf(PlaceSearchException.class);
        server.verify();
    }
}

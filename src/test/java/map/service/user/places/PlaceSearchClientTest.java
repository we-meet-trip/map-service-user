package map.service.user.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import map.service.user.places.dto.PlaceSearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * PlaceSearchClientTest — hub /v1/places 호출 어댑터 단위 테스트
 *
 * 이름이 바뀌어 나가는 두 자리를 못박는다. 화면이 보내는 query 는 hub 에서
 * keyword 이고, category 는 category_group_code 다. 이 대응이 어긋나면
 * 검색어가 통째로 무시되어도 오류 없이 "결과 없음"으로만 보인다.
 *
 * 값이 없는 조건은 아예 싣지 않는 것도 함께 본다 — 빈 문자열을 실어 보내면
 * hub 가 그것을 검색어로 받아 아무것도 찾지 못한다.
 */
@DisplayName("PlaceSearchClient 장소 검색 단위 테스트")
class PlaceSearchClientTest {

    private MockRestServiceServer server;
    private PlaceSearchClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://hub:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PlaceSearchClient(builder.build());
    }

    @Test
    @DisplayName("검색어와 분류는 hub 의 이름으로 바꿔 싣는다")
    void mapsQueryAndCategoryToHubNames() {
        String json = """
                {"places":[
                  {"content_id":"kakao:1","source":"kakao","name":"성수동 카페",
                   "address":"서울 성동구","road_address":"서울 성동구 연무장길 1",
                   "lat":37.54,"lng":127.05,"category":"음식점 / 카페",
                   "category_group_code":"CE7","place_url":"http://place/1"}
                ],"count":1,"sources":{"kakao":1}}""";
        server.expect(requestTo(startsWith("http://hub:8000/v1/places")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("province", "%EC%84%9C%EC%9A%B8%ED%8A%B9%EB%B3%84%EC%8B%9C"))
                .andExpect(queryParam("keyword", "%EC%B9%B4%ED%8E%98"))
                .andExpect(queryParam("category_group_code", "CE7"))
                .andExpect(queryParam("size", "10"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        PlaceSearchResponse response = client.search(
                "서울특별시", null, "카페", "CE7", null, 10);

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.places().get(0).contentId()).isEqualTo("kakao:1");
        assertThat(response.places().get(0).categoryGroupCode()).isEqualTo("CE7");
        server.verify();
    }

    @Test
    @DisplayName("비어 있는 조건은 싣지 않는다")
    void omitsBlankParameters() {
        server.expect(requestTo("http://hub:8000/v1/places?province=%EA%B0%95%EC%9B%90%ED%8A%B9%EB%B3%84%EC%9E%90%EC%B9%98%EB%8F%84"))
                .andRespond(withSuccess(
                        "{\"places\":[],\"count\":0,\"sources\":{}}",
                        MediaType.APPLICATION_JSON));

        client.search("강원특별자치도", "  ", "", null, null, null);

        server.verify();
    }

    @Test
    @DisplayName("hub 오류는 PlaceSearchException 으로 올린다")
    void propagatesHubError() {
        server.expect(requestTo(startsWith("http://hub:8000/v1/places")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.search(
                "서울특별시", null, "카페", null, null, null))
                .isInstanceOf(PlaceSearchException.class);
        server.verify();
    }
}

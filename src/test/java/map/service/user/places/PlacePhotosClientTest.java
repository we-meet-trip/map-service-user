package map.service.user.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import map.service.user.places.dto.PlacePhotosResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * PlacePhotosClientTest — hub /v1/places/photos 호출 어댑터 단위 테스트
 *
 * hub 의 snake_case 응답이 record 로 파싱되는지, 좌표가 쿼리에 실리는지,
 * hub 오류(5xx)가 PlacePhotosException 으로 바뀌는지 검증한다.
 */
@DisplayName("PlacePhotosClient 단위 테스트")
class PlacePhotosClientTest {

    private MockRestServiceServer server;
    private PlacePhotosClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://hub:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PlacePhotosClient(builder.build());
    }

    @Test
    @DisplayName("정상 응답 — PlacePhotosResponse 파싱 및 좌표 전달")
    void fetchParsesResponse() {
        String json = """
                {"query":"경복궁","photos":[
                  {"photo_uri":"https://lh3.example/img=w800",
                   "width_px":1600,"height_px":1200,
                   "attributions":[{"display_name":"홍길동",
                                    "uri":"https://maps.example/u/1"}],
                   "google_maps_uri":"https://maps.example/p/1",
                   "flag_content_uri":"https://maps.example/f/1"}
                ],"count":1}""";
        // 장소명은 대부분 한글이라 쿼리에 실릴 때 UTF-8 퍼센트 인코딩된다.
        // 기대값도 같은 방식으로 만들어, 인코딩이 어긋나면 여기서 걸린다.
        String encodedName = URLEncoder.encode("경복궁", StandardCharsets.UTF_8);
        server.expect(requestTo(startsWith("http://hub:8000/v1/places/photos")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("query", encodedName))
                .andExpect(queryParam("lat", "37.5663"))
                .andExpect(queryParam("lng", "126.9779"))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        PlacePhotosResponse response = client.fetch("경복궁", 37.5663, 126.9779);

        assertThat(response.query()).isEqualTo("경복궁");
        assertThat(response.count()).isEqualTo(1);
        assertThat(response.photos()).hasSize(1);
        assertThat(response.photos().get(0).photoUri())
                .isEqualTo("https://lh3.example/img=w800");
        assertThat(response.photos().get(0).widthPx()).isEqualTo(1600);
        assertThat(response.photos().get(0).googleMapsUri())
                .isEqualTo("https://maps.example/p/1");
        assertThat(response.photos().get(0).attributions()).hasSize(1);
        assertThat(response.photos().get(0).attributions().get(0).displayName())
                .isEqualTo("홍길동");
        server.verify();
    }

    @Test
    @DisplayName("사진 없음 — 빈 목록으로 파싱(오류 아님)")
    void fetchParsesEmptyPhotos() {
        server.expect(requestTo(startsWith("http://hub:8000/v1/places/photos")))
                .andRespond(withSuccess(
                        "{\"query\":\"x\",\"photos\":[],\"count\":0}",
                        MediaType.APPLICATION_JSON));

        PlacePhotosResponse response = client.fetch("x", 37.5, 127.0);

        assertThat(response.count()).isZero();
        assertThat(response.photos()).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("hub 5xx — PlacePhotosException 으로 변환")
    void fetchThrowsOnUpstreamError() {
        server.expect(requestTo(startsWith("http://hub:8000/v1/places/photos")))
                .andRespond(withServerError().body("upstream boom"));

        assertThatThrownBy(() -> client.fetch("경복궁", 37.5663, 126.9779))
                .isInstanceOf(PlacePhotosException.class)
                .satisfies(e -> {
                    PlacePhotosException ex = (PlacePhotosException) e;
                    assertThat(ex.statusCode()).isEqualTo(500);
                    assertThat(ex.body()).contains("upstream boom");
                });
    }
}

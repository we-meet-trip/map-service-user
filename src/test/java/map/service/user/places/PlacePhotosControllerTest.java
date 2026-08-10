package map.service.user.places;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import map.service.user.global.ratelimit.RateLimitFilter;
import map.service.user.global.security.JwtAuthenticationFilter;
import map.service.user.places.dto.PhotoAttribution;
import map.service.user.places.dto.PlacePhotoItem;
import map.service.user.places.dto.PlacePhotosResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PlacePhotosControllerTest — 장소 사진 엔드포인트 단위 테스트 (MockMvc)
 *
 * 검증: 정상 조회 200 + 본문(사진 URL·출처 표기), 사진 없음 200,
 * 좌표 누락·범위 위반·query 공백 시 400 과 hub 미호출.
 */
@WebMvcTest(PlacePhotosController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PlacePhotosController 단위 테스트 (MockMvc)")
class PlacePhotosControllerTest {

    private static final double LAT = 37.5663;
    private static final double LNG = 126.9779;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PlacePhotosClient client;
    // @WebMvcTest 는 서블릿 Filter 빈(JWT/RateLimit)을 컨텍스트에 포함하므로,
    // 실제 의존성(JwtService/RateLimitService) 없이 로드되도록 필터를 모킹한다.
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean private RateLimitFilter rateLimitFilter;

    @Test
    @DisplayName("정상 조회 — 200 OK 및 사진 본문 반환")
    void photos_valid_returns200() throws Exception {
        PlacePhotosResponse response = new PlacePhotosResponse(
                "경복궁",
                List.of(new PlacePhotoItem(
                        "https://lh3.example/img=w800", 1600, 1200,
                        List.of(new PhotoAttribution(
                                "홍길동", "https://maps.example/u/1")),
                        "https://maps.example/p/1",
                        "https://maps.example/f/1")),
                1);
        when(client.fetch(eq("경복궁"), anyDouble(), anyDouble()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/places/photos")
                        .param("query", "경복궁")
                        .param("lat", String.valueOf(LAT))
                        .param("lng", String.valueOf(LNG)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("경복궁"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.photos[0].photo_uri")
                        .value("https://lh3.example/img=w800"))
                .andExpect(jsonPath("$.photos[0].width_px").value(1600))
                .andExpect(jsonPath("$.photos[0].attributions[0].display_name")
                        .value("홍길동"))
                .andExpect(jsonPath("$.photos[0].google_maps_uri")
                        .value("https://maps.example/p/1"));
    }

    @Test
    @DisplayName("사진 없음 — 200 OK 및 빈 목록(오류 아님)")
    void photos_none_returns200EmptyList() throws Exception {
        when(client.fetch(eq("무명장소"), anyDouble(), anyDouble()))
                .thenReturn(new PlacePhotosResponse("무명장소", List.of(), 0));

        mockMvc.perform(get("/api/v1/places/photos")
                        .param("query", "무명장소")
                        .param("lat", String.valueOf(LAT))
                        .param("lng", String.valueOf(LNG)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("query 공백 — 400 Bad Request 이며 hub 를 부르지 않는다")
    void photos_blankQuery_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/places/photos")
                        .param("query", "  ")
                        .param("lat", String.valueOf(LAT))
                        .param("lng", String.valueOf(LNG)))
                .andExpect(status().isBadRequest());
        verify(client, never()).fetch(
                org.mockito.ArgumentMatchers.anyString(),
                anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("좌표 누락 — 400 Bad Request")
    void photos_missingCoordinate_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/places/photos")
                        .param("query", "경복궁")
                        .param("lat", String.valueOf(LAT)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("위도 범위 초과 — 400 Bad Request")
    void photos_latOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/places/photos")
                        .param("query", "경복궁")
                        .param("lat", "43.5")
                        .param("lng", String.valueOf(LNG)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("경도 범위 미달 — 400 Bad Request")
    void photos_lngOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/places/photos")
                        .param("query", "경복궁")
                        .param("lat", String.valueOf(LAT))
                        .param("lng", "120.0"))
                .andExpect(status().isBadRequest());
    }
}

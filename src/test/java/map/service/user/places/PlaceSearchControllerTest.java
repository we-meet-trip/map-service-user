package map.service.user.places;

import static org.mockito.ArgumentMatchers.any;
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
import map.service.user.places.dto.PlaceSearchResponse;
import map.service.user.places.dto.PlaceSearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PlaceSearchControllerTest — 장소 검색 엔드포인트 단위 테스트 (MockMvc)
 *
 * 장소를 직접 골라 일정을 짜는 화면이 부르는 자리다. 지역이 없으면 검색
 * 범위가 정해지지 않아 거절하고, 결과 수 상한은 hub 계약과 같은 1~15 로
 * 묶는다.
 */
@WebMvcTest(PlaceSearchController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PlaceSearchController 단위 테스트 (MockMvc)")
class PlaceSearchControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PlaceSearchClient client;
    // @WebMvcTest 는 서블릿 Filter 빈을 컨텍스트에 포함하므로 실제 의존성
    // 없이 로드되도록 모킹한다.
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean private RateLimitFilter rateLimitFilter;

    private static PlaceSearchResponse oneResult() {
        return new PlaceSearchResponse(
                List.of(new PlaceSearchResult(
                        "kakao:1", "kakao", "성수동 카페", "서울 성동구",
                        "서울 성동구 연무장길 1", 37.54, 127.05, "음식점 / 카페",
                        null, "CE7", null, "http://place/1",
                        null, null, null, null, null, null)),
                1,
                java.util.Map.of("kakao", 1));
    }

    @Test
    @DisplayName("정상 조회 — 200 과 장소 목록")
    void search_valid_returns200() throws Exception {
        when(client.search(eq("서울특별시"), any(), any(), any(), any(), any()))
                .thenReturn(oneResult());

        mockMvc.perform(get("/api/v1/places/search")
                        .param("province", "서울특별시")
                        .param("query", "카페"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.places[0].content_id").value("kakao:1"))
                .andExpect(jsonPath("$.places[0].place_url").value("http://place/1"));
    }

    @Test
    @DisplayName("지역이 없으면 400 — 검색 범위를 정할 수 없다")
    void search_withoutProvince_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/places/search").param("query", "카페"))
                .andExpect(status().isBadRequest());

        verify(client, never()).search(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("결과 수 상한을 벗어나면 400")
    void search_withSizeOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/places/search")
                        .param("province", "서울특별시")
                        .param("size", "16"))
                .andExpect(status().isBadRequest());

        verify(client, never()).search(any(), any(), any(), any(), any(), any());
    }
}

package map.service.user.weather;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import map.service.user.global.ratelimit.RateLimitFilter;
import map.service.user.global.security.JwtAuthenticationFilter;
import map.service.user.weather.dto.WeatherHomeResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WeatherControllerTest — 홈 날씨 엔드포인트 단위 테스트 (MockMvc)
 *
 * 검증: 정상 조회 200 + 본문 키, 국내 밖 좌표 400, 값 없는 항목의 키 생략.
 */
@WebMvcTest(WeatherController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("WeatherController 단위 테스트 (MockMvc)")
class WeatherControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private WeatherHomeService service;
    // @WebMvcTest 는 서블릿 Filter 빈을 컨텍스트에 포함하므로, 실제 의존성
    // 없이 로드되도록 필터를 모킹한다.
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean private RateLimitFilter rateLimitFilter;

    @Test
    @DisplayName("정상 조회 — 200 OK 및 카드 본문 반환")
    void home_valid_returns200() throws Exception {
        when(service.fetchHome(anyDouble(), anyDouble())).thenReturn(
                new WeatherHomeResponse(
                        27.3, 0, "맑음", -1.8, 31, 24, 30,
                        21, 11, "좋음", "좋음",
                        "2026-08-01T10:00:00+09:00",
                        "2026-08-01T10:00:00+09:00",
                        "기상청, 한국환경공단 제공"));

        mockMvc.perform(get("/api/v1/weather/home")
                        .param("lat", "37.5665").param("lng", "126.9780"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temp").value(27.3))
                .andExpect(jsonPath("$.yesterday_diff").value(-1.8))
                .andExpect(jsonPath("$.temp_max").value(31))
                .andExpect(jsonPath("$.pm10_grade").value("좋음"))
                .andExpect(jsonPath("$.attribution")
                        .value("기상청, 한국환경공단 제공"));
    }

    @Test
    @DisplayName("값 없는 항목은 키째 빠진다")
    void home_missingFields_areOmitted() throws Exception {
        when(service.fetchHome(anyDouble(), anyDouble())).thenReturn(
                new WeatherHomeResponse(
                        27.3, null, null, null, null, null, null,
                        null, null, null, null, null, null,
                        "기상청, 한국환경공단 제공"));

        mockMvc.perform(get("/api/v1/weather/home")
                        .param("lat", "37.5665").param("lng", "126.9780"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.yesterday_diff").doesNotExist())
                .andExpect(jsonPath("$.sky").doesNotExist())
                .andExpect(jsonPath("$.pm10").doesNotExist());
    }

    @Test
    @DisplayName("국내 범위 밖 좌표 — 400")
    void home_outOfCountry_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/weather/home")
                        .param("lat", "10.0").param("lng", "100.0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("좌표 누락 — 400")
    void home_missingParam_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/weather/home").param("lat", "37.5"))
                .andExpect(status().isBadRequest());
    }
}

package map.service.user.mobility;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
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
import map.service.user.mobility.dto.BikeStation;
import map.service.user.mobility.dto.BikeStationsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BikeStationControllerTest — 대여소 엔드포인트 단위 테스트 (MockMvc)
 *
 * 검증: 정상 조회 200 + 본문, 반경 기본값 적용, 서비스 지역 밖의 빈 목록이
 * 오류가 아님, 좌표·반경 위반 시 400 과 hub 미호출.
 */
@WebMvcTest(BikeStationController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("BikeStationController 단위 테스트 (MockMvc)")
class BikeStationControllerTest {

    private static final String LAT = "37.5665";
    private static final String LNG = "126.9780";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private BikeStationClient client;
    // @WebMvcTest 는 서블릿 Filter 빈(JWT/RateLimit)을 컨텍스트에 포함하므로,
    // 실제 의존성(JwtService/RateLimitService) 없이 로드되도록 필터를 모킹한다.
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean private RateLimitFilter rateLimitFilter;

    @Test
    @DisplayName("정상 조회 — 200 OK 및 대여소 본문 반환")
    void bikeStations_valid_returns200() throws Exception {
        BikeStationsResponse response = new BikeStationsResponse(
                "ok",
                List.of(new BikeStation(
                        "ST-4", "102. 망원역 1번출구 앞",
                        15, 5, 37.5556488, 126.91062927)),
                1);
        when(client.fetch(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/mobility/bike-stations")
                        .param("lat", LAT)
                        .param("lng", LNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.stations[0].station_id").value("ST-4"))
                .andExpect(jsonPath("$.stations[0].rack_total").value(15))
                .andExpect(jsonPath("$.stations[0].parking_bike_total").value(5));
    }

    @Test
    @DisplayName("반경 미지정 — 기본 반경으로 hub 를 부른다")
    void bikeStations_defaultRadius_isApplied() throws Exception {
        when(client.fetch(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(new BikeStationsResponse("ok", List.of(), 0));

        mockMvc.perform(get("/api/v1/mobility/bike-stations")
                        .param("lat", LAT)
                        .param("lng", LNG))
                .andExpect(status().isOk());

        verify(client).fetch(anyDouble(), anyDouble(), eq(5000));
    }

    @Test
    @DisplayName("서비스 지역 밖 — 200 OK 및 빈 목록(오류 아님)")
    void bikeStations_outsideArea_returns200EmptyList() throws Exception {
        when(client.fetch(anyDouble(), anyDouble(), anyInt()))
                .thenReturn(new BikeStationsResponse("ok", List.of(), 0));

        mockMvc.perform(get("/api/v1/mobility/bike-stations")
                        .param("lat", "35.1796")
                        .param("lng", "129.0756"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("좌표 누락 — 400 Bad Request 이며 hub 를 부르지 않는다")
    void bikeStations_missingCoordinate_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/mobility/bike-stations")
                        .param("lat", LAT))
                .andExpect(status().isBadRequest());

        verify(client, never()).fetch(anyDouble(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("위도 범위 초과 — 400 Bad Request 이며 hub 를 부르지 않는다")
    void bikeStations_latOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/mobility/bike-stations")
                        .param("lat", "32.9")
                        .param("lng", LNG))
                .andExpect(status().isBadRequest());

        verify(client, never()).fetch(anyDouble(), anyDouble(), anyInt());
    }

    @Test
    @DisplayName("반경 범위 초과 — 400 Bad Request 이며 hub 를 부르지 않는다")
    void bikeStations_radiusOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/mobility/bike-stations")
                        .param("lat", LAT)
                        .param("lng", LNG)
                        .param("radiusM", "20001"))
                .andExpect(status().isBadRequest());

        verify(client, never()).fetch(anyDouble(), anyDouble(), anyInt());
    }
}

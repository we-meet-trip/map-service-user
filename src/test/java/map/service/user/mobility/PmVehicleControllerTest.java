package map.service.user.mobility;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import map.service.user.global.ratelimit.RateLimitFilter;
import map.service.user.global.security.JwtAuthenticationFilter;
import map.service.user.mobility.dto.PmVehicle;
import map.service.user.mobility.dto.PmVehiclesResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PmVehicleControllerTest — 공유 킥보드 엔드포인트 단위 테스트 (MockMvc)
 *
 * 검증: 정상 조회 200 + 본문, 반경 기본값 적용, city 선택 파라미터 전달,
 * 기기 없음이 오류가 아님, 좌표·반경 위반 시 400 과 hub 미호출.
 */
@WebMvcTest(PmVehicleController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PmVehicleController 단위 테스트 (MockMvc)")
class PmVehicleControllerTest {

    private static final String LAT = "37.5665";
    private static final String LNG = "126.9780";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PmVehicleClient client;
    // @WebMvcTest 는 서블릿 Filter 빈(JWT/RateLimit)을 컨텍스트에 포함하므로,
    // 실제 의존성(JwtService/RateLimitService) 없이 로드되도록 필터를 모킹한다.
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean private RateLimitFilter rateLimitFilter;

    @Test
    @DisplayName("정상 조회 — 200 OK 및 기기 본문 반환")
    void pmVehicles_valid_returns200() throws Exception {
        PmVehiclesResponse response = new PmVehiclesResponse(
                "ok",
                List.of(new PmVehicle(
                        "Beam", "D-1", 82, "전동킥보드", 37.5670, 126.9790)),
                1);
        when(client.fetch(anyDouble(), anyDouble(), anyInt(), any()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/mobility/pm-vehicles")
                        .param("lat", LAT)
                        .param("lng", LNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.vehicles[0].provider").value("Beam"))
                .andExpect(jsonPath("$.vehicles[0].device_id").value("D-1"))
                .andExpect(jsonPath("$.vehicles[0].battery_level").value(82))
                .andExpect(jsonPath("$.vehicles[0].vehicle_type")
                        .value("전동킥보드"));
    }

    @Test
    @DisplayName("반경 미지정 — 기본 반경으로 hub 를 부른다")
    void pmVehicles_defaultRadius_isApplied() throws Exception {
        when(client.fetch(anyDouble(), anyDouble(), anyInt(), any()))
                .thenReturn(new PmVehiclesResponse("ok", List.of(), 0));

        mockMvc.perform(get("/api/v1/mobility/pm-vehicles")
                        .param("lat", LAT)
                        .param("lng", LNG))
                .andExpect(status().isOk());

        verify(client).fetch(anyDouble(), anyDouble(), eq(1000), isNull());
    }

    @Test
    @DisplayName("city 지정 — 그대로 hub 로 전달된다")
    void pmVehicles_city_isForwarded() throws Exception {
        when(client.fetch(anyDouble(), anyDouble(), anyInt(), any()))
                .thenReturn(new PmVehiclesResponse("ok", List.of(), 0));

        mockMvc.perform(get("/api/v1/mobility/pm-vehicles")
                        .param("lat", LAT)
                        .param("lng", LNG)
                        .param("city", "서울특별시"))
                .andExpect(status().isOk());

        verify(client).fetch(
                anyDouble(), anyDouble(), anyInt(), eq("서울특별시"));
    }

    @Test
    @DisplayName("주변에 기기 없음 — 200 OK 및 빈 목록(오류 아님)")
    void pmVehicles_none_returns200EmptyList() throws Exception {
        when(client.fetch(anyDouble(), anyDouble(), anyInt(), any()))
                .thenReturn(new PmVehiclesResponse("ok", List.of(), 0));

        mockMvc.perform(get("/api/v1/mobility/pm-vehicles")
                        .param("lat", LAT)
                        .param("lng", LNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("좌표 누락 — 400 Bad Request 이며 hub 를 부르지 않는다")
    void pmVehicles_missingCoordinate_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/mobility/pm-vehicles")
                        .param("lat", LAT))
                .andExpect(status().isBadRequest());

        verify(client, never())
                .fetch(anyDouble(), anyDouble(), anyInt(), any());
    }

    @Test
    @DisplayName("위도 범위 초과 — 400 Bad Request 이며 hub 를 부르지 않는다")
    void pmVehicles_latOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/mobility/pm-vehicles")
                        .param("lat", "43.1")
                        .param("lng", LNG))
                .andExpect(status().isBadRequest());

        verify(client, never())
                .fetch(anyDouble(), anyDouble(), anyInt(), any());
    }

    @Test
    @DisplayName("반경 범위 초과 — 400 Bad Request 이며 hub 를 부르지 않는다")
    void pmVehicles_radiusOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/mobility/pm-vehicles")
                        .param("lat", LAT)
                        .param("lng", LNG)
                        .param("radiusM", "99"))
                .andExpect(status().isBadRequest());

        verify(client, never())
                .fetch(anyDouble(), anyDouble(), anyInt(), any());
    }
}

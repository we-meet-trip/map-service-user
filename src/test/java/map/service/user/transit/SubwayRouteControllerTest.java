package map.service.user.transit;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import map.service.user.global.ratelimit.RateLimitFilter;
import map.service.user.global.security.JwtAuthenticationFilter;
import map.service.user.transit.dto.SubwayRoute;
import map.service.user.transit.dto.SubwayRouteResponse;
import map.service.user.transit.dto.SubwayRouteStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SubwayRouteControllerTest — 지하철 경로 엔드포인트 단위 테스트 (MockMvc)
 *
 * 검증: 정상 조회 200 + 본문, 경로 없음·조회 불가가 서로 다른 값으로 나가는지,
 * 좌표 누락·범위 위반 시 400 과 hub 미호출.
 */
@WebMvcTest(SubwayRouteController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("SubwayRouteController 단위 테스트 (MockMvc)")
class SubwayRouteControllerTest {

    private static final String START_LAT = "37.4979";
    private static final String START_LNG = "127.0276";
    private static final String END_LAT = "37.5663";
    private static final String END_LNG = "126.9779";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private SubwayRouteClient client;
    // @WebMvcTest 는 서블릿 Filter 빈(JWT/RateLimit)을 컨텍스트에 포함하므로,
    // 실제 의존성(JwtService/RateLimitService) 없이 로드되도록 필터를 모킹한다.
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean private RateLimitFilter rateLimitFilter;

    @Test
    @DisplayName("정상 조회 — 200 OK 및 경로 본문 반환")
    void subway_valid_returns200() throws Exception {
        SubwayRouteResponse response = new SubwayRouteResponse(
                "ok",
                new SubwayRoute(42, 1500, 1, 620, List.of(
                        new SubwayRouteStep(
                                "walk", null, "출발지", "강남", 4, null),
                        new SubwayRouteStep(
                                "subway", "수도권 2호선", "강남", "시청", 34, 11))));
        when(client.fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/transit/subway")
                        .param("startLat", START_LAT)
                        .param("startLng", START_LNG)
                        .param("endLat", END_LAT)
                        .param("endLng", END_LNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.route.total_time_min").value(42))
                .andExpect(jsonPath("$.route.fare").value(1500))
                .andExpect(jsonPath("$.route.transfer_count").value(1))
                .andExpect(jsonPath("$.route.total_walk_m").value(620))
                .andExpect(jsonPath("$.route.steps[1].line_name")
                        .value("수도권 2호선"))
                .andExpect(jsonPath("$.route.steps[1].station_count").value(11));
    }

    @Test
    @DisplayName("경로 없음 — 200 OK 이며 status 로 구분된다")
    void subway_notFound_returns200() throws Exception {
        when(client.fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new SubwayRouteResponse("not_found", null));

        mockMvc.perform(get("/api/v1/transit/subway")
                        .param("startLat", START_LAT)
                        .param("startLng", START_LNG)
                        .param("endLat", END_LAT)
                        .param("endLng", END_LNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("not_found"));
    }

    @Test
    @DisplayName("조회 불가 — 경로 없음과 다른 값이라 화면이 문구를 가를 수 있다")
    void subway_unavailable_isDistinctFromNotFound() throws Exception {
        when(client.fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new SubwayRouteResponse("unavailable", null));

        mockMvc.perform(get("/api/v1/transit/subway")
                        .param("startLat", START_LAT)
                        .param("startLng", START_LNG)
                        .param("endLat", END_LAT)
                        .param("endLng", END_LNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unavailable"));
    }

    @Test
    @DisplayName("좌표 누락 — 400 Bad Request 이며 hub 를 부르지 않는다")
    void subway_missingCoordinate_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transit/subway")
                        .param("startLat", START_LAT)
                        .param("startLng", START_LNG)
                        .param("endLat", END_LAT))
                .andExpect(status().isBadRequest());

        verify(client, never())
                .fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("위도 범위 초과 — 400 Bad Request 이며 hub 를 부르지 않는다")
    void subway_latOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transit/subway")
                        .param("startLat", "43.1")
                        .param("startLng", START_LNG)
                        .param("endLat", END_LAT)
                        .param("endLng", END_LNG))
                .andExpect(status().isBadRequest());

        verify(client, never())
                .fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("경도 범위 초과 — 400 Bad Request 이며 hub 를 부르지 않는다")
    void subway_lngOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transit/subway")
                        .param("startLat", START_LAT)
                        .param("startLng", START_LNG)
                        .param("endLat", END_LAT)
                        .param("endLng", "132.1"))
                .andExpect(status().isBadRequest());

        verify(client, never())
                .fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }
}

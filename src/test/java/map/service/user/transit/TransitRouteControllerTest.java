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
import map.service.user.transit.dto.TransitRouteLeg;
import map.service.user.transit.dto.TransitRouteOption;
import map.service.user.transit.dto.TransitRouteOptionsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * TransitRouteControllerTest — 통합 길찾기 엔드포인트 단위 테스트 (MockMvc)
 *
 * SubwayRouteControllerTest 와 같은 검증 축이되, 응답이 단일 route 가 아니라
 * routes 목록이라는 점을 함께 본다.
 */
@WebMvcTest(TransitRouteController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("TransitRouteController 단위 테스트 (MockMvc)")
class TransitRouteControllerTest {

    private static final String START_LAT = "37.4979";
    private static final String START_LNG = "127.0276";
    private static final String END_LAT = "37.5663";
    private static final String END_LNG = "126.9779";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private TransitRouteClient client;
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean private RateLimitFilter rateLimitFilter;

    @Test
    @DisplayName("정상 조회 — 200 OK 및 경로 후보 목록 반환")
    void routes_valid_returns200() throws Exception {
        TransitRouteOptionsResponse response = new TransitRouteOptionsResponse(
                "ok",
                List.of(
                        new TransitRouteOption(28, 1650, 2, 903,
                                List.of("subway"),
                                List.of(new TransitRouteLeg(
                                        "subway", "수도권 9호선", "언주", "신논현",
                                        2, 1,
                                        List.of(List.of(37.507323, 127.033909)),
                                        List.of("언주", "신논현")))),
                        new TransitRouteOption(44, 1750, 2, 314,
                                List.of("subway", "bus"),
                                List.of(new TransitRouteLeg(
                                        "bus", null, "신림동별빛거리입구", "여의도역3번출구",
                                        20, 11,
                                        List.of(), List.of())))));
        when(client.fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/transit/routes")
                        .param("startLat", START_LAT)
                        .param("startLng", START_LNG)
                        .param("endLat", END_LAT)
                        .param("endLng", END_LNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.routes").isArray())
                .andExpect(jsonPath("$.routes.length()").value(2))
                .andExpect(jsonPath("$.routes[0].modes[0]").value("subway"))
                .andExpect(jsonPath("$.routes[1].modes[1]").value("bus"))
                .andExpect(jsonPath("$.routes[1].legs[0].line_name")
                        .doesNotExist());
    }

    @Test
    @DisplayName("경로 없음 — 200 OK 이며 status 로 구분된다")
    void routes_notFound_returns200() throws Exception {
        when(client.fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new TransitRouteOptionsResponse("not_found", List.of()));

        mockMvc.perform(get("/api/v1/transit/routes")
                        .param("startLat", START_LAT)
                        .param("startLng", START_LNG)
                        .param("endLat", END_LAT)
                        .param("endLng", END_LNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("not_found"));
    }

    @Test
    @DisplayName("조회 불가 — 경로 없음과 다른 값이라 화면이 문구를 가를 수 있다")
    void routes_unavailable_isDistinctFromNotFound() throws Exception {
        when(client.fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new TransitRouteOptionsResponse("unavailable", List.of()));

        mockMvc.perform(get("/api/v1/transit/routes")
                        .param("startLat", START_LAT)
                        .param("startLng", START_LNG)
                        .param("endLat", END_LAT)
                        .param("endLng", END_LNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unavailable"));
    }

    @Test
    @DisplayName("좌표 누락 — 400 Bad Request 이며 hub 를 부르지 않는다")
    void routes_missingCoordinate_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transit/routes")
                        .param("startLat", START_LAT)
                        .param("startLng", START_LNG)
                        .param("endLat", END_LAT))
                .andExpect(status().isBadRequest());

        verify(client, never())
                .fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    @DisplayName("위도 범위 초과 — 400 Bad Request 이며 hub 를 부르지 않는다")
    void routes_latOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transit/routes")
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
    void routes_lngOutOfRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transit/routes")
                        .param("startLat", START_LAT)
                        .param("startLng", START_LNG)
                        .param("endLat", END_LAT)
                        .param("endLng", "132.1"))
                .andExpect(status().isBadRequest());

        verify(client, never())
                .fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }
}

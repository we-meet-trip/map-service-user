package map.service.user.transit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import map.service.user.global.ratelimit.RateLimitFilter;
import map.service.user.global.security.JwtAuthenticationFilter;
import map.service.user.transit.dto.TransitLaneRequest;
import map.service.user.transit.dto.TransitLaneResponse;
import map.service.user.transit.dto.TransitWalkRequest;
import map.service.user.transit.dto.TransitWalkResponse;
import map.service.user.trip.HubDirectionsClient;
import map.service.user.trip.dto.HubDirectionsDtos.LegReq;
import map.service.user.trip.dto.HubDirectionsDtos.Route;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.TestPropertySource;
import map.service.user.transit.dto.TransitRouteLeg;
import map.service.user.transit.dto.TransitRouteOption;
import map.service.user.transit.dto.TransitRouteOptionsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
// 도보 연결선은 기본 꺼짐이라 켠 상태로 시험한다. 꺼진 경우는 따로 본다.
@TestPropertySource(properties = "transit-walk.enabled=true")
@DisplayName("TransitRouteController 단위 테스트 (MockMvc)")
class TransitRouteControllerTest {

    private static final String START_LAT = "37.4979";
    private static final String START_LNG = "127.0276";
    private static final String END_LAT = "37.5663";
    private static final String END_LNG = "126.9779";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private TransitRouteClient client;
    @MockitoBean private HubDirectionsClient directions;
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean private RateLimitFilter rateLimitFilter;

    @Test
    @DisplayName("정상 조회 — 200 OK 및 경로 후보 목록 반환")
    void routes_valid_returns200() throws Exception {
        TransitRouteOptionsResponse response = new TransitRouteOptionsResponse(
                "ok",
                List.of(
                        new TransitRouteOption(28, 1650, 2, 903, 1200, 0, 0.0,
                                List.of("subway"),
                                List.of(new TransitRouteLeg(
                                        "subway", "수도권 9호선", "언주", "신논현",
                                        2, 1, 1200,
                                        List.of(List.of(37.507323, 127.033909)),
                                        List.of("언주", "신논현"))),
                                "18:2:132:136@204:2:917:915"),
                        new TransitRouteOption(44, 1750, 2, 314, 1200, 8300, 0.874,
                                List.of("subway", "bus"),
                                List.of(new TransitRouteLeg(
                                        "bus", null, "신림동별빛거리입구", "여의도역3번출구",
                                        20, 11, 8300,
                                        List.of(), List.of())),
                                null)));
        when(client.fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString()))
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
        when(client.fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString()))
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
        when(client.fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString()))
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
                .fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString());
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
                .fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString());
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
                .fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString());
    }

    @Test
    @DisplayName("mode 를 안 주면 all 로 hub 에 넘긴다")
    void routes_defaultMode_isAll() throws Exception {
        when(client.fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString()))
                .thenReturn(new TransitRouteOptionsResponse("not_found", List.of()));

        mockMvc.perform(get("/api/v1/transit/routes")
                        .param("startLat", START_LAT)
                        .param("startLng", START_LNG)
                        .param("endLat", END_LAT)
                        .param("endLng", END_LNG))
                .andExpect(status().isOk());

        verify(client).fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq("all"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"all", "subway", "bus"})
    @DisplayName("mode 는 받은 값을 그대로 hub 로 넘긴다 — 거르는 규칙은 hub 가 정한다")
    void routes_passesModeThrough(String mode) throws Exception {
        when(client.fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString()))
                .thenReturn(new TransitRouteOptionsResponse("not_found", List.of()));

        mockMvc.perform(get("/api/v1/transit/routes")
                        .param("startLat", START_LAT)
                        .param("startLng", START_LNG)
                        .param("endLat", END_LAT)
                        .param("endLng", END_LNG)
                        .param("mode", mode))
                .andExpect(status().isOk());

        verify(client).fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble(), eq(mode));
    }

    @Test
    @DisplayName("모르는 mode — 400 이며 hub 를 부르지 않는다")
    void routes_unknownMode_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/transit/routes")
                        .param("startLat", START_LAT)
                        .param("startLng", START_LNG)
                        .param("endLat", END_LAT)
                        .param("endLng", END_LNG)
                        .param("mode", "taxi"))
                .andExpect(status().isBadRequest());

        verify(client, never())
                .fetch(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyString());
    }

    // ── POST /api/v1/transit/routes/lane ─────────────────────────────

    private static final String LANE_URL = "/api/v1/transit/routes/lane";

    @Test
    @DisplayName("노선 좌표 — 본문을 그대로 넘기고 geometries 를 돌려준다")
    void lane_valid_returns200() throws Exception {
        when(client.fetchLane(any())).thenReturn(new TransitLaneResponse(
                "ok", List.of(List.of(), List.of(List.of(37.5663, 126.9779)))));

        mockMvc.perform(post(LANE_URL)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"map_obj\":\"18:2:132:136@204:2:917:915\","
                                + "\"types\":[\"walk\",\"subway\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.geometries.length()").value(2))
                .andExpect(jsonPath("$.geometries[1][0][0]").value(37.5663));

        verify(client).fetchLane(new TransitLaneRequest(
                "18:2:132:136@204:2:917:915", List.of("walk", "subway")));
    }

    @Test
    @DisplayName("노선 좌표 — hub 가 못 주면 200 + unavailable(client 는 직선 유지)")
    void lane_unavailable_passesThrough() throws Exception {
        when(client.fetchLane(any())).thenReturn(TransitLaneResponse.unavailable());

        mockMvc.perform(post(LANE_URL)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"map_obj\":\"1:2:3:4\",\"types\":[\"subway\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unavailable"))
                .andExpect(jsonPath("$.geometries.length()").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"map_obj\":\"1:2;DROP\",\"types\":[\"subway\"]}",  // 허용 안 된 문자
            "{\"map_obj\":\"\",\"types\":[\"subway\"]}",           // 빈 값
            "{\"map_obj\":\"1:2:3:4\",\"types\":[]}",              // 구간 없음
            "{\"map_obj\":\"1:2:3:4\",\"types\":[\"taxi\"]}",      // 모르는 종류
            "{\"types\":[\"subway\"]}"                             // map_obj 누락
    })
    @DisplayName("노선 좌표 — 형식이 틀리면 400 이며 hub 를 부르지 않는다")
    void lane_invalidBody_returns400(String body) throws Exception {
        mockMvc.perform(post(LANE_URL)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(client, never()).fetchLane(any());
    }

    // ── POST /api/v1/transit/routes/walk ─────────────────────────────

    private static final String WALK_URL = "/api/v1/transit/routes/walk";
    private static final org.springframework.http.MediaType JSON =
            org.springframework.http.MediaType.APPLICATION_JSON;

    private static String segmentsJson(int count) {
        String one = "{\"start_lat\":37.5665,\"start_lng\":126.9780,"
                + "\"end_lat\":37.5657,\"end_lng\":126.9769}";
        return "{\"segments\":["
                + String.join(",", java.util.Collections.nCopies(count, one)) + "]}";
    }

    private static Route osrm(int points) {
        List<List<Double>> path = new java.util.ArrayList<>();
        for (int i = 0; i < points; i++) {
            path.add(List.of(37.5665 - i * 0.0004, 126.9780 - i * 0.0005));
        }
        return new Route(path, 120, 90, "OSRM", "foot");
    }

    @Test
    @DisplayName("도보 연결선 — 구간을 도보로 넘기고 받은 경로를 같은 순서로 돌려준다")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void walk_mapsSegmentsAndReturnsPaths() throws Exception {
        when(directions.fetchRoutes(eq("walk"), anyList()))
                .thenReturn(java.util.Arrays.asList(osrm(3), null));

        mockMvc.perform(post(WALK_URL).contentType(JSON).content(segmentsJson(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.paths.length()").value(2))
                .andExpect(jsonPath("$.paths[0].length()").value(3))
                // 못 받은 자리는 빈 목록 — client 는 그 연결선만 직선으로 둔다.
                .andExpect(jsonPath("$.paths[1].length()").value(0));

        ArgumentCaptor<List> legs = ArgumentCaptor.forClass(List.class);
        verify(directions).fetchRoutes(eq("walk"), legs.capture());
        assertThat(legs.getValue()).hasSize(2);
        LegReq first = (LegReq) legs.getValue().get(0);
        assertThat(first.start().lat()).isEqualTo(37.5665);
        assertThat(first.goal().lng()).isEqualTo(126.9769);
        // 역 이름 대신 중립 표기 — 이동 경로를 hub 기록에 남기지 않는다.
        assertThat(first.startName()).isEqualTo("도보");
        assertThat(first.goalName()).isEqualTo("도보");
    }

    @Test
    @DisplayName("도보 연결선 — 스텁(가짜 직선) 경로는 쓰지 않는다")
    void walk_stubRoutesAreDropped() throws Exception {
        when(directions.fetchRoutes(eq("walk"), anyList())).thenReturn(List.of(new Route(
                List.of(List.of(37.5665, 126.9780), List.of(37.5657, 126.9769)),
                0, 0, "STUB", null)));

        mockMvc.perform(post(WALK_URL).contentType(JSON).content(segmentsJson(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unavailable"))
                .andExpect(jsonPath("$.paths.length()").value(0));
    }

    @Test
    @DisplayName("도보 연결선 — hub 가 전부 실패하면(null) 200 + unavailable")
    void walk_allFailed_unavailable() throws Exception {
        when(directions.fetchRoutes(eq("walk"), anyList())).thenReturn(null);

        mockMvc.perform(post(WALK_URL).contentType(JSON).content(segmentsJson(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unavailable"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"segments\":[]}",                                              // 구간 없음
            "{}",                                                             // segments 누락
            "{\"segments\":[{\"start_lat\":43.5,\"start_lng\":126.9,"
                    + "\"end_lat\":37.5,\"end_lng\":126.9}]}",                // 위도 범위 밖
            "{\"segments\":[{\"start_lat\":37.5,\"start_lng\":126.9,"
                    + "\"end_lat\":37.5}]}"                                   // end_lng 누락
    })
    @DisplayName("도보 연결선 — 형식이 틀리면 400 이며 hub 를 부르지 않는다")
    void walk_invalidBody_returns400(String body) throws Exception {
        mockMvc.perform(post(WALK_URL).contentType(JSON).content(body))
                .andExpect(status().isBadRequest());

        verify(directions, never()).fetchRoutes(any(), any());
    }

    @Test
    @DisplayName("도보 연결선 — 21개 이상이면 400(hub 한 번 조회 상한 20)")
    void walk_tooManySegments_returns400() throws Exception {
        mockMvc.perform(post(WALK_URL).contentType(JSON).content(segmentsJson(21)))
                .andExpect(status().isBadRequest());

        verify(directions, never()).fetchRoutes(any(), any());
    }

    @Test
    @DisplayName("도보 연결선 — 플래그가 꺼져 있으면 hub 를 부르지 않는다")
    void walk_disabled_makesNoCall() {
        TransitRouteController off = new TransitRouteController(client, directions, false);

        TransitWalkResponse res = off.walk(new TransitWalkRequest(List.of(
                new TransitWalkRequest.Segment(37.5665, 126.9780, 37.5657, 126.9769))));

        assertThat(res.status()).isEqualTo("unavailable");
        verify(directions, never()).fetchRoutes(any(), any());
    }
}

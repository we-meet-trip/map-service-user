package map.service.user.recommend;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import map.service.user.global.ratelimit.RateLimitFilter;
import map.service.user.global.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * RecommendControllerTest — edit 엔드포인트의 좌표 검증 단위 테스트 (MockMvc)
 *
 * 검증: 한국 범위 내 좌표는 200(applyEdit 위임), 범위 밖 좌표는 @Valid
 * cascade 로 400. draft 존재/부재 분기(404)는 RecommendService 책임이라
 * 여기서 다루지 않는다.
 */
@WebMvcTest(RecommendController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("RecommendController edit 좌표 검증 (MockMvc)")
class RecommendControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private RecommendService service;
    // @WebMvcTest 는 서블릿 Filter 빈(JWT/RateLimit)을 컨텍스트에 포함하므로,
    // 실제 의존성 없이 로드되도록 필터를 모킹한다(ReviewSearchControllerTest 와 동일).
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean private RateLimitFilter rateLimitFilter;

    private static String placesBody(double lat, double lng) {
        return """
                {"places":[{"place_id":1,"name":"경복궁","address":"서울",
                "lat":%s,"lng":%s,"recommended_visit_time":"오전"}]}
                """.formatted(lat, lng);
    }

    @Test
    void getPreservesLegacySuccessfulPublicJsonExactly() throws Exception {
        String payload = """
                {"job_id":"job-success","status":"done","places":[],"visit_order":[],"legs":[],
                 "clothing":"가벼운 겉옷","error":null,"retry_after_seconds":null,
                 "warnings":["날씨 미확인"],"timeline_status":"unverified"}""";
        when(service.findOwnedDraft(eq("job-success"), any())).thenReturn(Optional.of(payload));

        mockMvc.perform(get("/api/v1/recommend/job-success"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(payload));
    }

    @Test
    void getPreservesTerminalFailureCodeAndRetryablePublicJsonExactly() throws Exception {
        String payload = """
                {"job_id":"job-failed","status":"failed",
                 "error":"장소 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.",
                 "code":"upstream_unavailable","retryable":true}""";
        when(service.findOwnedDraft(eq("job-failed"), any())).thenReturn(Optional.of(payload));

        mockMvc.perform(get("/api/v1/recommend/job-failed"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(payload));
    }

    @Test
    @DisplayName("한국 범위 내 좌표 — 200 OK 및 머지 결과 반환")
    void edit_validCoords_returns200() throws Exception {
        // 소유자 확인과 멱등키가 붙어 인자가 넷이다. 토큰이 없는 요청이라
        // 사용자와 멱등키는 비어 온다.
        when(service.applyEdit(eq("job-1"), any(), any(), any()))
                .thenReturn(Optional.of("{\"ok\":true}"));

        mockMvc.perform(post("/api/v1/recommend/job-1/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placesBody(37.5, 127.0)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("위도 범위(33~43) 밖 — 400 Bad Request")
    void edit_latOutOfRange_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/recommend/job-1/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placesBody(10.0, 127.0)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("경도 범위(124~132) 밖 — 400 Bad Request")
    void edit_lngOutOfRange_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/recommend/job-1/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placesBody(37.5, 200.0)))
                .andExpect(status().isBadRequest());
    }
}

package map.service.user.schedule;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import map.service.user.global.exception.ErrorCode;
import map.service.user.global.ratelimit.RateLimitFilter;
import map.service.user.global.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * ScheduleControllerTest — 저장 엔드포인트의 소유자 요구 검증 (MockMvc)
 *
 * 저장 응답 200 은 "저장됐고 다시 꺼낼 수 있다"는 뜻이어야 한다. 목록·상세·삭제가
 * 전부 소유자 조건으로 조회하므로, 주인 없이 저장된 행은 어떤 경로로도 다시 열 수
 * 없기 때문이다. 그래서 소유자를 특정하지 못한 요청은 저장을 시도하지 않고 401 이다.
 *
 * 세 갈래를 본다.
 *   1) 소유자가 있으면 그대로 저장하고 schedule_id 를 돌려준다
 *   2) 토큰이 아예 없으면 401 (사유는 유효하지 않은 토큰)
 *   3) 토큰을 들고 왔는데 만료돼 거절된 경우에는 만료 사유를 그대로 실어 준다 —
 *      클라이언트가 그 사유를 보고 토큰을 갱신해 다시 시도한다
 */
@WebMvcTest(ScheduleController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ScheduleController 저장 소유자 요구 (MockMvc)")
class ScheduleControllerTest {

    private static final String BODY = """
            {"job_id":"11111111-1111-1111-1111-111111111111",
             "title":"속초 2박 3일",
             "date_start":"2026-08-10","date_end":"2026-08-12",
             "transport":"walk","active_start_hour":9,"active_end_hour":21}
            """;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ScheduleService service;
    // @WebMvcTest 는 서블릿 Filter 빈을 컨텍스트에 포함하므로 실제 의존성 없이
    // 로드되도록 모킹한다(RecommendControllerTest 와 동일).
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean private RateLimitFilter rateLimitFilter;

    /** addFilters=false 라 필터가 돌지 않으므로 principal 을 직접 세운다. */
    private static RequestPostProcessor owner(Long userId) {
        return request -> {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId, null, java.util.List.of()));
            return request;
        };
    }

    @Test
    @DisplayName("소유자가 있으면 저장하고 schedule_id 를 돌려준다")
    void save_withOwner_returnsScheduleId() throws Exception {
        when(service.persist(any(), any())).thenReturn(7L);

        mockMvc.perform(post("/api/v1/schedules")
                        .with(owner(42L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule_id").value(7));

        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("토큰이 없으면 저장하지 않고 401")
    void save_withoutOwner_returns401AndDoesNotPersist() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(post("/api/v1/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verify(service, never()).persist(any(), any());
    }

    @Test
    @DisplayName("만료된 토큰이면 그 사유를 실어 401 — 클라이언트 갱신 경로가 걸리게")
    void save_withRejectedToken_returnsExpiredCode() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(post("/api/v1/schedules")
                        .requestAttr(JwtAuthenticationFilter.REJECTED_TOKEN_ATTR,
                                ErrorCode.EXPIRED_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.EXPIRED_TOKEN.getCode()));

        verify(service, never()).persist(any(), any());
    }

    @Test
    @DisplayName("재추천 — 새 추천 작업을 띄우고 202 로 job_id 를 준다")
    void replan_returnsAcceptedJob() throws Exception {
        when(service.replan(7L, 42L)).thenReturn(
                new map.service.user.recommend.dto.JobAccepted(
                        "job-9", "in_progress", 3));

        mockMvc.perform(post("/api/v1/schedules/7/replan").with(owner(42L)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.job_id").value("job-9"))
                .andExpect(jsonPath("$.status").value("in_progress"));

        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("재추천 — 토큰이 없으면 시도하지 않고 401")
    void replan_withoutOwner_returns401() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(post("/api/v1/schedules/7/replan"))
                .andExpect(status().isUnauthorized());

        verify(service, never()).replan(any(), any());
    }

    @Test
    @DisplayName("알림 무시 — 204 로 답하고 서비스에 위임한다")
    void dismissWeatherAlert_returnsNoContent() throws Exception {
        mockMvc.perform(post("/api/v1/schedules/7/weather-alert/dismiss")
                        .with(owner(42L)))
                .andExpect(status().isNoContent());

        verify(service).dismissWeatherAlert(7L, 42L);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("알림 무시 — 토큰이 없으면 시도하지 않고 401")
    void dismissWeatherAlert_withoutOwner_returns401() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(post("/api/v1/schedules/7/weather-alert/dismiss"))
                .andExpect(status().isUnauthorized());

        verify(service, never()).dismissWeatherAlert(any(), any());
    }
}

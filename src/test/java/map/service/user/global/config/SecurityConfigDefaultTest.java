package map.service.user.global.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import map.service.user.global.jwt.JwtService;
import map.service.user.global.ratelimit.ClientIpResolver;
import map.service.user.global.ratelimit.RateLimitFilter;
import map.service.user.global.ratelimit.RateLimitService;
import map.service.user.global.security.JwtAuthenticationFilter;
import map.service.user.recommend.RecommendController;
import map.service.user.recommend.RecommendService;
import map.service.user.schedule.ScheduleController;
import map.service.user.schedule.ScheduleSaveRequest;
import map.service.user.schedule.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;
import map.service.user.recommend.dto.JobAccepted;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SecurityConfigDefaultTest — auth.enforced=false(기본) 동작 검증
 *
 * 플래그 미설정 시 보호 대상 엔드포인트가 토큰 없이도 도달 가능(permitAll)한지,
 * 그리고 익명 요청에서 @AuthenticationPrincipal Long 이 ClassCastException 없이
 * null 로 해석되는지 확인한다. 경로가 열려 있다는 것과 소유자를 요구한다는 것은
 * 다른 층위라, 저장은 경로가 열려 있어도 소유자가 없으면 막힌다.
 * 실제 SecurityConfig 필터 체인과 실 JWT/RateLimit 필터, 익명 인증 필터를 그대로 쓴다.
 */
@WebMvcTest({RecommendController.class, ScheduleController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        RateLimitFilter.class, ClientIpResolver.class, CorsProperties.class})
@DisplayName("SecurityConfig 기본(플래그 off) 테스트")
class SecurityConfigDefaultTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private JwtService jwtService;
    @MockitoBean private RateLimitService rateLimitService;
    @MockitoBean private RecommendService recommendService;
    @MockitoBean private ScheduleService scheduleService;

    @BeforeEach
    void setUp() {
        when(recommendService.findDraft(anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("토큰 없이 보호 대상 엔드포인트 도달 가능 (401 아님)")
    void protectedEndpointReachableWithoutToken() throws Exception {
        // draft 미존재 → 컨트롤러가 202 Accepted 반환. 401 이 아니므로 공개(permitAll)가 유지됨.
        mockMvc.perform(get("/api/v1/recommend/job-1"))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("익명 POST — 익명 principal 이 null 로 해석되고, 저장은 401 로 막힌다")
    void anonymousPrincipalResolvesToNullAndSaveIsRejected() throws Exception {
        // 익명 principal("anonymousUser" String)은 Long 파라미터로 캐스팅되지 않아
        // 리졸버가 null 을 주입한다 — 여기서 ClassCastException 이 나면 500 이 된다.
        // null 이 들어왔음은 컨트롤러가 401 로 막았다는 사실로 확인한다.
        //
        // 플래그가 꺼져 있어 경로 자체는 열려 있지만(permitAll), 주인 없이 저장한
        // 일정은 목록·상세·삭제 어디서도 다시 꺼낼 수 없어 저장을 시도하지 않는다.
        mockMvc.perform(post("/api/v1/schedules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"job_id\":\"11111111-1111-1111-1111-111111111111\","
                                + "\"title\":\"t\",\"date_start\":\"2026-07-06\","
                                + "\"date_end\":\"2026-07-07\"}"))
                .andExpect(status().isUnauthorized());

        verify(scheduleService, never())
                .persist(any(ScheduleSaveRequest.class), any());
    }

    @Test
    @DisplayName("익명 POST /recommend — principal 이 null 로 들어가고 202 로 접수된다")
    void anonymousRecommendCreateReachesControllerWithNullPrincipal()
            throws Exception {
        // 추천 접수는 인증을 요구하지 않는 공개 경로다. 익명 principal 은
        // "anonymousUser" 문자열이라 Long 파라미터로 캐스팅되지 않는데,
        // 여기서 리졸버가 예외를 던지면 익명 트래픽 전체가 500 이 된다.
        // 정상 접수(202)와 서비스로 null 이 전달된 사실로 확인한다.
        when(recommendService.createRecommendationDetailed(any(), any()))
                .thenReturn(new RecommendService.RecommendationResult(
                        new JobAccepted("job-anon", "in_progress", 3), false));

        mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":{\"date_start\":\"2026-07-06\","
                                + "\"date_end\":\"2026-07-06\","
                                + "\"time_start\":\"09:00:00\","
                                + "\"time_end\":\"18:00:00\"},"
                                + "\"province\":\"서울특별시\",\"city\":\"강남구\"}"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<Long> userId = ArgumentCaptor.forClass(Long.class);
        verify(recommendService).createRecommendationDetailed(
                any(), userId.capture());
        assertThat(userId.getValue()).isNull();
    }
}

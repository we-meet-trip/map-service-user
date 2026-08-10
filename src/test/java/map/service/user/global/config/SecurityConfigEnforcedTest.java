package map.service.user.global.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;
import map.service.user.global.jwt.JwtService;
import map.service.user.global.ratelimit.ClientIpResolver;
import map.service.user.global.ratelimit.RateLimitFilter;
import map.service.user.global.ratelimit.RateLimitService;
import map.service.user.global.security.JwtAuthenticationFilter;
import map.service.user.recommend.RecommendController;
import map.service.user.recommend.RecommendService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SecurityConfigEnforcedTest — auth.enforced=true 인가 시행 동작 검증
 *
 * - 토큰/인증 없이 보호 엔드포인트 접근 → 401.
 * - 인증 principal 이 존재하면 통과.
 * - 인증 공개 엔드포인트(/api/v1/auth/login)는 시행 상태에서도 공개.
 * 실제 SecurityConfig 필터 체인과 실 JWT/RateLimit 필터를 사용한다.
 */
@WebMvcTest(RecommendController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        RateLimitFilter.class, ClientIpResolver.class, CorsProperties.class})
@TestPropertySource(properties = "auth.enforced=true")
@DisplayName("SecurityConfig 인가 시행(플래그 on) 테스트")
class SecurityConfigEnforcedTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private JwtService jwtService;
    @MockitoBean private RateLimitService rateLimitService;
    @MockitoBean private RecommendService recommendService;

    @BeforeEach
    void setUp() {
        when(recommendService.findDraft(anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("토큰 없이 보호 엔드포인트 접근 → 401")
    void protectedEndpointWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/recommend/job-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("인증 principal 존재 시 보호 엔드포인트 통과")
    void protectedEndpointWithAuthenticationPasses() throws Exception {
        // draft 미존재 → 202 Accepted. 인증이 있으면 인가를 통과함을 증명.
        mockMvc.perform(get("/api/v1/recommend/job-1")
                        .with(authentication(new UsernamePasswordAuthenticationToken(
                                1L, null, List.of()))))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("인증 공개 엔드포인트(/api/v1/auth/login)는 시행 상태에서도 401 아님")
    void authLoginStillPermitted() throws Exception {
        // 레이트리밋 필터가 login 라우트를 매칭하므로 통과하도록 스텁.
        when(rateLimitService.isAllowed(anyString(), anyInt(), any())).thenReturn(true);

        // 본 슬라이스에는 AuthController 핸들러가 없어 요청은 401 이 아닌 다른 코드로
        // 끝나지만(핸들러 부재), 핵심은 인가 계층이 401 로 막지 않고 통과시킨다는 점이다.
        mockMvc.perform(post("/api/v1/auth/login"))
                .andExpect(result -> org.assertj.core.api.Assertions
                        .assertThat(result.getResponse().getStatus())
                        .as("auth/login must not be blocked by 401 under enforcement")
                        .isNotEqualTo(401));
    }
}

package map.service.user.domain.user;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Claims;
import java.time.LocalDate;
import java.util.List;
import map.service.user.chat.service.ChatRealtimeService;
import map.service.user.domain.user.controller.UserController;
import map.service.user.domain.user.dto.UserMeResponse;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.service.AccountWithdrawalService;
import map.service.user.domain.user.service.UserProfileService;
import map.service.user.global.config.CorsProperties;
import map.service.user.global.config.SecurityConfig;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.jwt.JwtService;
import map.service.user.global.ratelimit.ClientIpResolver;
import map.service.user.global.ratelimit.RateLimitFilter;
import map.service.user.global.ratelimit.RateLimitService;
import map.service.user.global.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * UserMeSecurityTest — 내 정보 경로의 인증 필수 검증
 *
 * 인가 시행 스위치가 기본값(꺼짐)이어도 /api/v1/users/me 는 토큰이 있어야
 * 한다. 공개로 두면 두 가지가 깨진다 — 누구의 정보인지 정해지지 않은 채로
 * 처리가 시작되고, 만료된 토큰이 401 을 못 받아 클라이언트의 토큰 갱신
 * 흐름이 이 화면에서만 멈춘다.
 *
 * 실제 필터체인과 실 JWT 필터를 그대로 태운다.
 */
@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class,
        RateLimitFilter.class, ClientIpResolver.class, CorsProperties.class})
@DisplayName("users/me 인증 필수 테스트")
class UserMeSecurityTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private JwtService jwtService;
    @MockitoBean private RateLimitService rateLimitService;
    @MockitoBean private UserProfileService userProfileService;
    @MockitoBean private AccountWithdrawalService withdrawalService;
    @MockitoBean private ChatRealtimeService realtimeService;

    @Test
    @DisplayName("토큰 없이 접근하면 401 (인가 시행이 꺼져 있어도)")
    void requiresTokenByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                // 본문 없는 401 이어야 클라이언트가 상태 코드만 보고 갱신을 건다.
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("만료된 토큰도 401 — 갱신 흐름이 여기서 멈추지 않는다")
    void expiredTokenAlsoReturns401() throws Exception {
        when(jwtService.validateAccessToken("expired"))
                .thenThrow(new CustomException(ErrorCode.EXPIRED_TOKEN));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer expired"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("유효 토큰이면 내 정보를 돌려준다")
    void validTokenReturnsProfile() throws Exception {
        Claims claims = Mockito.mock(Claims.class);
        when(jwtService.validateAccessToken("tok")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn(7L);
        when(userProfileService.getMe(7L)).thenReturn(new UserMeResponse(
                7L, "a@b.c", "테스터", null, AuthProvider.EMAIL, false,
                LocalDate.of(1998, 3, 2), "여성",
                List.of("맛집 🍜"), List.of("food")));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("테스터"))
                .andExpect(jsonPath("$.interests[0]").value("맛집 🍜"))
                .andExpect(jsonPath("$.themes[0]").value("food"));
    }

    @Test
    @DisplayName("탈퇴도 토큰이 있어야 한다 — 남의 계정을 지울 수 있으면 안 된다")
    void withdrawRequiresToken() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());

        Mockito.verifyNoInteractions(withdrawalService);
    }

    @Test
    @DisplayName("유효 토큰이면 토큰 주인의 계정을 지운다")
    void validTokenWithdrawsOwnAccount() throws Exception {
        Claims claims = Mockito.mock(Claims.class);
        when(jwtService.validateAccessToken("tok")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn(7L);
        when(withdrawalService.withdraw(7L, "tok")).thenReturn(List.of());

        mockMvc.perform(delete("/api/v1/users/me")
                        .header("Authorization", "Bearer tok"))
                .andExpect(status().isNoContent());

        Mockito.verify(withdrawalService).withdraw(7L, "tok");
    }
}

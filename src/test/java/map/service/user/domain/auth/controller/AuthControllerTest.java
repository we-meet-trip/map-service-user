package map.service.user.domain.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import map.service.user.domain.auth.dto.response.AuthResponse;
import map.service.user.domain.auth.service.AuthService;
import map.service.user.domain.auth.service.KakaoOAuthService;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.jwt.JwtService;
import map.service.user.global.ratelimit.RateLimitFilter;
import map.service.user.global.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)  // OncePerRequestFilter.doFilter() is final — bypass filters entirely
@DisplayName("AuthController 단위 테스트 (MockMvc)")
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private AuthService             authService;
    @MockitoBean private KakaoOAuthService       kakaoOAuthService;
    @MockitoBean private JwtService              jwtService;
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean private RateLimitFilter         rateLimitFilter;

    private AuthResponse mockAuthResponse;

    @BeforeEach
    void setUp() {
        mockAuthResponse = AuthResponse.builder()
                .accessToken("mock.access.token")
                .refreshToken("mock-raw-refresh")
                .accessTokenExpiresIn(3600)
                .refreshTokenExpiresIn(2592000)
                .user(AuthResponse.UserInfo.builder()
                        .id(1L)
                        .email("test@example.com")
                        .nickname("테스터")
                        .authProvider(AuthProvider.EMAIL)
                        .emailVerified(false)
                        .build())
                .build();
    }

    // ── POST /api/v1/auth/signup ─────────────────────────────────────────────

    @Test
    @DisplayName("회원가입 — 유효한 요청 시 201 Created 및 토큰 반환")
    void signUp_validRequest_returns201() throws Exception {
        when(authService.signUp(any())).thenReturn(mockAuthResponse);

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "test@example.com",
                                "password", "password123!",
                                "nickname", "테스터"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("mock.access.token"))
                .andExpect(jsonPath("$.refreshToken").value("mock-raw-refresh"))
                .andExpect(jsonPath("$.user.email").value("test@example.com"));
    }

    @Test
    @DisplayName("회원가입 — 이메일 형식 오류 시 400 Bad Request")
    void signUp_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "not-an-email",
                                "password", "password123!",
                                "nickname", "테스터"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("회원가입 — 비밀번호 8자 미만 시 400 Bad Request")
    void signUp_shortPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "test@example.com",
                                "password", "short",
                                "nickname", "테스터"
                        ))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("회원가입 — 이메일 중복 시 409 Conflict")
    void signUp_duplicateEmail_returns409() throws Exception {
        when(authService.signUp(any())).thenThrow(new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS));

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "dup@example.com",
                                "password", "password123!",
                                "nickname", "중복유저"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
    }

    // ── POST /api/v1/auth/login ──────────────────────────────────────────────

    @Test
    @DisplayName("로그인 — 유효한 요청 시 200 OK 및 토큰 반환")
    void login_validRequest_returns200() throws Exception {
        when(authService.login(any())).thenReturn(mockAuthResponse);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "test@example.com",
                                "password", "password123!"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("mock.access.token"));
    }

    @Test
    @DisplayName("로그인 — 잘못된 자격증명 시 401 Unauthorized")
    void login_invalidCredentials_returns401() throws Exception {
        when(authService.login(any())).thenThrow(new CustomException(ErrorCode.INVALID_CREDENTIALS));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "test@example.com",
                                "password", "wrongpass"
                        ))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_002"));
    }

    // ── POST /api/v1/auth/kakao/callback ────────────────────────────────────

    @Test
    @DisplayName("카카오 콜백 — 유효한 code 시 200 OK 및 토큰 반환")
    void kakaoCallback_validCode_returns200() throws Exception {
        when(kakaoOAuthService.processLogin(any())).thenReturn(mockAuthResponse);

        mockMvc.perform(post("/api/v1/auth/kakao/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "kakao-auth-code-12345"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    @DisplayName("카카오 콜백 — code 누락 시 400 Bad Request")
    void kakaoCallback_missingCode_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/kakao/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ── POST /api/v1/auth/logout ─────────────────────────────────────────────

    @Test
    @DisplayName("로그아웃 — Authorization 헤더 포함 시 204 No Content")
    void logout_withAuthHeader_returns204() throws Exception {
        doNothing().when(authService).logout(any(), any());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer mock.access.token"))
                .andExpect(status().isNoContent());
    }

    // ── POST /api/v1/auth/token/refresh ─────────────────────────────────────

    @Test
    @DisplayName("토큰 갱신 — 유효한 refresh token 시 200 OK")
    void refreshToken_validToken_returns200() throws Exception {
        when(authService.refreshTokens(any())).thenReturn(mockAuthResponse);

        mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refreshToken", "valid-refresh-token"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("mock.access.token"));
    }
}

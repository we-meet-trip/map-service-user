package map.service.user.domain.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import map.service.user.domain.auth.dto.response.AuthResponse;
import map.service.user.domain.auth.service.AuthService;
import map.service.user.domain.auth.service.KakaoOAuthService;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.global.jwt.JwtService;
import map.service.user.global.ratelimit.RateLimitFilter;
import map.service.user.global.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.restdocs.AutoConfigureRestDocs;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.headers.HeaderDocumentation.headerWithName;
import static org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.*;
import static org.springframework.restdocs.payload.JsonFieldType.STRING;
import static org.springframework.restdocs.payload.PayloadDocumentation.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@AutoConfigureRestDocs
@DisplayName("AuthController REST Docs")
class AuthControllerRestDocsTest {

    @Autowired private MockMvc       mockMvc;
    @Autowired private ObjectMapper  objectMapper;

    @MockitoBean private AuthService             authService;
    @MockitoBean private KakaoOAuthService       kakaoOAuthService;
    @MockitoBean private JwtService              jwtService;
    @MockitoBean private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean private RateLimitFilter         rateLimitFilter;

    private AuthResponse mockAuthResponse;

    @BeforeEach
    void setUp() {
        mockAuthResponse = AuthResponse.builder()
                .accessToken("eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxIn0...")
                .refreshToken("550e8400-e29b-41d4-a716-446655440000")
                .accessTokenExpiresIn(3600)
                .refreshTokenExpiresIn(2592000)
                .user(AuthResponse.UserInfo.builder()
                        .id(1L)
                        .email("traveler@example.com")
                        .nickname("여행자")
                        .authProvider(AuthProvider.EMAIL)
                        .emailVerified(false)
                        .build())
                .build();
    }

    @Test
    @DisplayName("이메일 회원가입")
    void signUp() throws Exception {
        when(authService.signUp(any())).thenReturn(mockAuthResponse);

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "traveler@example.com",
                                "password", "password123!",
                                "nickname", "여행자"
                        ))))
                .andExpect(status().isCreated())
                .andDo(document("auth/signup",
                        requestFields(
                                fieldWithPath("email").description("이메일"),
                                fieldWithPath("password").description("비밀번호 (8자 이상, 100자 이하)"),
                                fieldWithPath("nickname").description("닉네임 (1~50자)"),
                                fieldWithPath("deviceToken").type(STRING).description("FCM/APNs 푸시 토큰 (선택)").optional(),
                                fieldWithPath("deviceType").type(STRING).description("기기 타입 — IOS | ANDROID | WEB (선택)").optional()
                        ),
                        responseFields(
                                fieldWithPath("accessToken").description("Access Token (RS256 JWT, 1시간)"),
                                fieldWithPath("refreshToken").description("Refresh Token (UUID, 30일)"),
                                fieldWithPath("accessTokenExpiresIn").description("Access Token 만료 시간(초)"),
                                fieldWithPath("refreshTokenExpiresIn").description("Refresh Token 만료 시간(초)"),
                                fieldWithPath("user.id").description("사용자 ID (Long)"),
                                fieldWithPath("user.email").description("이메일"),
                                fieldWithPath("user.nickname").description("닉네임"),
                                fieldWithPath("user.profileImageUrl").description("프로필 이미지 URL (null 가능)").optional(),
                                fieldWithPath("user.authProvider").description("인증 제공자 (EMAIL / KAKAO)"),
                                fieldWithPath("user.emailVerified").description("이메일 인증 여부")
                        )
                ));
    }

    @Test
    @DisplayName("이메일 로그인")
    void login() throws Exception {
        when(authService.login(any())).thenReturn(mockAuthResponse);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "traveler@example.com",
                                "password", "password123!"
                        ))))
                .andExpect(status().isOk())
                .andDo(document("auth/login",
                        requestFields(
                                fieldWithPath("email").description("이메일"),
                                fieldWithPath("password").description("비밀번호"),
                                fieldWithPath("deviceToken").type(STRING).description("FCM/APNs 푸시 토큰 (선택)").optional(),
                                fieldWithPath("deviceType").type(STRING).description("기기 타입 — IOS | ANDROID | WEB (선택)").optional()
                        ),
                        responseFields(
                                fieldWithPath("accessToken").description("Access Token (RS256 JWT, 1시간)"),
                                fieldWithPath("refreshToken").description("Refresh Token (UUID, 30일)"),
                                fieldWithPath("accessTokenExpiresIn").description("Access Token 만료 시간(초)"),
                                fieldWithPath("refreshTokenExpiresIn").description("Refresh Token 만료 시간(초)"),
                                fieldWithPath("user.id").description("사용자 ID (Long)"),
                                fieldWithPath("user.email").description("이메일"),
                                fieldWithPath("user.nickname").description("닉네임"),
                                fieldWithPath("user.profileImageUrl").description("프로필 이미지 URL (null 가능)").optional(),
                                fieldWithPath("user.authProvider").description("인증 제공자 (EMAIL / KAKAO)"),
                                fieldWithPath("user.emailVerified").description("이메일 인증 여부")
                        )
                ));
    }

    @Test
    @DisplayName("카카오 로그인 콜백")
    void kakaoCallback() throws Exception {
        when(kakaoOAuthService.processLogin(any())).thenReturn(mockAuthResponse);

        mockMvc.perform(post("/api/v1/auth/kakao/callback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", "kakao-auth-code-12345",
                                "deviceToken", "fcm-token-abc123",
                                "deviceType", "IOS"
                        ))))
                .andExpect(status().isOk())
                .andDo(document("auth/kakao-callback",
                        requestFields(
                                fieldWithPath("code").description("카카오 인가 코드 (Flutter SDK 발급)"),
                                fieldWithPath("deviceToken").type(STRING).description("FCM/APNs 푸시 토큰 (선택)").optional(),
                                fieldWithPath("deviceType").type(STRING).description("기기 타입 — IOS | ANDROID | WEB (선택)").optional()
                        ),
                        responseFields(
                                fieldWithPath("accessToken").description("Access Token (RS256 JWT, 1시간)"),
                                fieldWithPath("refreshToken").description("Refresh Token (UUID, 30일)"),
                                fieldWithPath("accessTokenExpiresIn").description("Access Token 만료 시간(초)"),
                                fieldWithPath("refreshTokenExpiresIn").description("Refresh Token 만료 시간(초)"),
                                fieldWithPath("user.id").description("사용자 ID (Long)"),
                                fieldWithPath("user.email").description("이메일 (카카오 동의 거부 시 null)").optional(),
                                fieldWithPath("user.nickname").description("닉네임"),
                                fieldWithPath("user.profileImageUrl").description("프로필 이미지 URL (null 가능)").optional(),
                                fieldWithPath("user.authProvider").description("인증 제공자 (EMAIL / KAKAO)"),
                                fieldWithPath("user.emailVerified").description("이메일 인증 여부")
                        )
                ));
    }

    @Test
    @DisplayName("로그아웃")
    void logout() throws Exception {
        doNothing().when(authService).logout(any(), any());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer eyJhbGciOiJSUzI1NiJ9..."))
                .andExpect(status().isNoContent())
                .andDo(document("auth/logout",
                        requestHeaders(
                                headerWithName("Authorization").description("Bearer {accessToken}")
                        )
                ));
    }

    @Test
    @DisplayName("토큰 재발급 (Refresh Token Rotation)")
    void refreshToken() throws Exception {
        when(authService.refreshTokens(any())).thenReturn(mockAuthResponse);

        mockMvc.perform(post("/api/v1/auth/token/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refreshToken", "550e8400-e29b-41d4-a716-446655440000"
                        ))))
                .andExpect(status().isOk())
                .andDo(document("auth/token-refresh",
                        requestFields(
                                fieldWithPath("refreshToken").description("Refresh Token (UUID 원문). 재사용 탐지 시 해당 계정의 모든 토큰 즉시 무효화.")
                        ),
                        responseFields(
                                fieldWithPath("accessToken").description("새 Access Token (RS256 JWT, 1시간)"),
                                fieldWithPath("refreshToken").description("새 Refresh Token (UUID, 30일) — 기존 토큰 즉시 무효화 (Refresh Token Rotation)"),
                                fieldWithPath("accessTokenExpiresIn").description("Access Token 만료 시간(초)"),
                                fieldWithPath("refreshTokenExpiresIn").description("Refresh Token 만료 시간(초)"),
                                fieldWithPath("user.id").description("사용자 ID (Long)"),
                                fieldWithPath("user.email").description("이메일"),
                                fieldWithPath("user.nickname").description("닉네임"),
                                fieldWithPath("user.profileImageUrl").description("프로필 이미지 URL (null 가능)").optional(),
                                fieldWithPath("user.authProvider").description("인증 제공자 (EMAIL / KAKAO)"),
                                fieldWithPath("user.emailVerified").description("이메일 인증 여부")
                        )
                ));
    }
}

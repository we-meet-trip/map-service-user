package map.service.user.domain.auth.service;

import map.service.user.domain.auth.dto.request.EmailLoginRequest;
import map.service.user.domain.auth.dto.request.EmailSignUpRequest;
import map.service.user.domain.auth.dto.request.TokenRefreshRequest;
import map.service.user.domain.auth.dto.response.AuthResponse;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.RefreshToken;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.RefreshTokenRepository;
import map.service.user.domain.user.repository.UserDeviceRepository;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AuthService 단위 테스트")
class AuthServiceTest {

    @Mock private UserRepository         userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private UserDeviceRepository   userDeviceRepository;
    @Mock private PasswordEncoder        passwordEncoder;
    @Mock private JwtService             jwtService;

    @InjectMocks
    private AuthService authService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .email("test@example.com")
                .nickname("테스터")
                .passwordHash("$2a$10$hashedpassword")
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(false)
                .build();

        when(jwtService.generateAccessToken(any())).thenReturn("mock.access.token");
        when(jwtService.generateRawRefreshToken()).thenReturn("mock-raw-refresh");
        when(jwtService.getAccessTokenExpirySeconds()).thenReturn(3600L);
        when(jwtService.getRefreshTokenExpirySeconds()).thenReturn(2592000L);
        when(refreshTokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    // ── signUp ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("새 이메일로 회원가입 성공 — 사용자 저장 및 토큰 반환")
    void signUp_newEmail_savesUserAndReturnsTokens() {
        EmailSignUpRequest request = makeSignUpRequest("new@example.com", "password123!", "신규유저");
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenReturn(testUser);

        AuthResponse response = authService.signUp(request);

        assertThat(response.getAccessToken()).isEqualTo("mock.access.token");
        assertThat(response.getRefreshToken()).isEqualTo("mock-raw-refresh");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("이미 존재하는 이메일로 회원가입 시 EMAIL_ALREADY_EXISTS 예외")
    void signUp_duplicateEmail_throwsEmailAlreadyExists() {
        EmailSignUpRequest request = makeSignUpRequest("dup@example.com", "password123!", "중복유저");
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.signUp(request))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.EMAIL_ALREADY_EXISTS));

        verify(userRepository, never()).save(any());
    }

    // ── login ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("올바른 이메일/비밀번호로 로그인 성공")
    void login_validCredentials_returnsTokens() {
        EmailLoginRequest request = makeLoginRequest("test@example.com", "password123!");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("password123!", testUser.getPasswordHash())).thenReturn(true);

        AuthResponse response = authService.login(request);

        assertThat(response.getAccessToken()).isEqualTo("mock.access.token");
    }

    @Test
    @DisplayName("존재하지 않는 이메일로 로그인 시 INVALID_CREDENTIALS 예외")
    void login_unknownEmail_throwsInvalidCredentials() {
        EmailLoginRequest request = makeLoginRequest("none@example.com", "password123!");
        when(userRepository.findByEmail("none@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    @DisplayName("틀린 비밀번호로 로그인 시 INVALID_CREDENTIALS 예외")
    void login_wrongPassword_throwsInvalidCredentials() {
        EmailLoginRequest request = makeLoginRequest("test@example.com", "wrongpass");
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));
        when(passwordEncoder.matches("wrongpass", testUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    // ── refreshTokens ────────────────────────────────────────────────────────

    @Test
    @DisplayName("유효한 refresh token으로 토큰 회전 성공 — 기존 토큰 revoke 후 신규 토큰 반환")
    void refreshTokens_validToken_rotatesAndReturnsNewTokens() throws Exception {
        String rawToken = "valid-uuid-token";
        String hash = AuthService.sha256Hex(rawToken);

        RefreshToken stored = RefreshToken.builder()
                .user(testUser)
                .tokenHash(hash)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();

        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.of(stored));

        TokenRefreshRequest request = makeRefreshRequest(rawToken);
        AuthResponse response = authService.refreshTokens(request);

        assertThat(stored.isRevoked()).isTrue();          // 기존 토큰 폐기 확인
        assertThat(response.getAccessToken()).isEqualTo("mock.access.token");
        assertThat(response.getRefreshToken()).isEqualTo("mock-raw-refresh");
    }

    @Test
    @DisplayName("존재하지 않는 refresh token으로 갱신 시 REFRESH_TOKEN_NOT_FOUND 예외")
    void refreshTokens_unknownToken_throwsNotFound() {
        String hash = AuthService.sha256Hex("nonexistent-token");
        when(refreshTokenRepository.findByTokenHash(hash)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refreshTokens(makeRefreshRequest("nonexistent-token")))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.REFRESH_TOKEN_NOT_FOUND));
    }

    @Test
    @DisplayName("sha256Hex — 동일 입력은 항상 동일 해시 반환")
    void sha256Hex_sameInput_returnsSameHash() {
        String hash1 = AuthService.sha256Hex("token-value");
        String hash2 = AuthService.sha256Hex("token-value");

        assertThat(hash1).isEqualTo(hash2).hasSize(64);
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private EmailSignUpRequest makeSignUpRequest(String email, String pw, String nick) {
        try {
            var r = new EmailSignUpRequest();
            setField(r, "email", email);
            setField(r, "password", pw);
            setField(r, "nickname", nick);
            return r;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private EmailLoginRequest makeLoginRequest(String email, String pw) {
        try {
            var r = new EmailLoginRequest();
            setField(r, "email", email);
            setField(r, "password", pw);
            return r;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private TokenRefreshRequest makeRefreshRequest(String token) {
        try {
            var r = new TokenRefreshRequest();
            setField(r, "refreshToken", token);
            return r;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

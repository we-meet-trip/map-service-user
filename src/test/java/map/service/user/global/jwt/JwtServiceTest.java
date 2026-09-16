package map.service.user.global.jwt;

import io.jsonwebtoken.Claims;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.User;
import map.service.user.global.config.JwtProperties;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("JwtService 단위 테스트")
class JwtServiceTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private JwtService jwtService;
    private User testUser;
    private KeyPair keyPair;
    private map.service.user.domain.user.repository.UserRepository users;
    private map.service.user.domain.user.repository.RefreshTokenRepository refreshTokens;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();

        JwtProperties props = new JwtProperties();
        props.setAccessTokenExpirySeconds(1800);
        props.setRefreshTokenExpirySeconds(1209600);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        users = org.mockito.Mockito.mock(map.service.user.domain.user.repository.UserRepository.class);
        org.mockito.Mockito.when(users.existsById(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        refreshTokens = org.mockito.Mockito.mock(map.service.user.domain.user.repository.RefreshTokenRepository.class);
        jwtService = new JwtService(keyPair, props, redisTemplate, users, refreshTokens);

        testUser = User.builder()
                .email("test@example.com")
                .nickname("테스터")
                .passwordHash("hashed")
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(true)
                .build();

        // JPA @GeneratedValue는 테스트에서 동작 안 함 — 리플렉션으로 직접 설정
        Field idField = User.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(testUser, 1L);
    }

    @Test
    @DisplayName("유효한 사용자로 access token 생성 성공")
    void generateAccessToken_validUser_returnsNonBlankToken() {
        String token = jwtService.generateAccessToken(testUser);

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);  // header.payload.signature
    }

    @Test
    @DisplayName("생성된 access token 검증 성공 및 Claims 반환")
    void validateAccessToken_validToken_returnsClaims() {
        String token = jwtService.generateAccessToken(testUser);

        Claims claims = jwtService.validateAccessToken(token);

        assertThat(claims.getSubject()).isNotNull();
        assertThat(claims.get("email")).isEqualTo("test@example.com");
        assertThat(claims.get("provider")).isEqualTo("EMAIL");
    }

    @Test
    @DisplayName("Claims에서 userId 추출 성공")
    void extractUserId_validClaims_returnsLong() {
        String token = jwtService.generateAccessToken(testUser);
        Claims claims = jwtService.validateAccessToken(token);

        Long userId = jwtService.extractUserId(claims);

        assertThat(userId).isNotNull().isEqualTo(1L);
    }

    @Test
    @DisplayName("변조된 토큰 검증 시 INVALID_TOKEN 예외")
    void validateAccessToken_tamperedToken_throwsInvalidToken() {
        String token = jwtService.generateAccessToken(testUser);
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + "invalidsignature";

        assertThatThrownBy(() -> jwtService.validateAccessToken(tampered))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_TOKEN));
    }

    @Test
    @DisplayName("blacklist에 등록된 토큰 검증 시 BLACKLISTED_TOKEN 예외")
    void validateAccessToken_blacklistedToken_throwsBlacklistedToken() {
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        String token = jwtService.generateAccessToken(testUser);

        assertThatThrownBy(() -> jwtService.validateAccessToken(token))
                .isInstanceOf(CustomException.class)
                .satisfies(ex -> assertThat(((CustomException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.BLACKLISTED_TOKEN));
    }

    @Test
    @DisplayName("access token blacklist 등록 — Redis set 호출 검증")
    void blacklistAccessToken_validToken_callsRedisSet() {
        String token = jwtService.generateAccessToken(testUser);

        jwtService.blacklistAccessToken(token);

        verify(valueOps).set(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("raw refresh token 생성 — UUID 형식")
    void generateRawRefreshToken_returnsUUIDString() {
        String raw = jwtService.generateRawRefreshToken();

        assertThat(raw).isNotBlank().hasSize(36);  // UUID 문자열 길이
    }

    /** 이미 만료된 토큰을 만들어 주는 발급기. 만료 폭을 초 단위로 지정한다. */
    private JwtService issuerExpiredBy(long secondsAgo) {
        JwtProperties props = new JwtProperties();
        props.setAccessTokenExpirySeconds(-secondsAgo);
        props.setRefreshTokenExpirySeconds(1209600);
        return new JwtService(keyPair, props, redisTemplate, users, refreshTokens);
    }

    @Test
    @DisplayName("만료 관용 — 유예 안이면 통과, 유예 밖이면 만료로 거절")
    void tolerantValidation_acceptsInsideGraceRejectsOutside() {
        String fresh = issuerExpiredBy(60).generateAccessToken(testUser);
        assertThat(jwtService.validateAccessTokenTolerantOfExpiry(fresh).getSubject()).isEqualTo("1");
        // 엄격 경로는 같은 토큰을 그대로 거절한다 — 보내는 쪽 기준은 바뀌지 않는다.
        assertThatThrownBy(() -> jwtService.validateAccessToken(fresh))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXPIRED_TOKEN);

        String stale = issuerExpiredBy(3600).generateAccessToken(testUser);
        assertThatThrownBy(() -> jwtService.validateAccessTokenTolerantOfExpiry(stale))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXPIRED_TOKEN);
    }

    @Test
    @DisplayName("만료 관용 — 폐기된 토큰은 만료를 봐주더라도 막는다")
    void tolerantValidation_stillRejectsRevoked() {
        String expired = issuerExpiredBy(60).generateAccessToken(testUser);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        assertThatThrownBy(() -> jwtService.validateAccessTokenTolerantOfExpiry(expired))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BLACKLISTED_TOKEN);
    }

    @Test
    @DisplayName("만료 관용 — 세션이 끊긴 토큰은 만료를 봐주더라도 막는다")
    void tolerantValidation_stillRejectsRevokedSession() {
        String expired = issuerExpiredBy(60).generateAccessToken(testUser, "sid-1");
        when(refreshTokens.existsBySessionIdAndRevokedAtIsNullAndExpiresAtAfter(
                org.mockito.ArgumentMatchers.eq("sid-1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);

        assertThatThrownBy(() -> jwtService.validateAccessTokenTolerantOfExpiry(expired))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.BLACKLISTED_TOKEN);
    }
}

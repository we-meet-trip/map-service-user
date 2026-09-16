package map.service.user.global.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import map.service.user.domain.user.entity.User;
import map.service.user.global.config.JwtProperties;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

/**
 * RS256 JWT 서비스
 *
 * Access Token 구조:
 *   sub  = user id (Long)
 *   jti  = 토큰 고유 ID (UUID, Redis blacklist 키로 사용)
 *   email
 *   provider = AuthProvider.name()
 *   iat, exp
 *
 * Refresh Token:
 *   서비스 내부에서 생성한 랜덤 UUID 문자열.
 *   원본(raw)은 클라이언트에만 전달, DB에는 SHA-256 해시 저장.
 */
@Component
public class JwtService {

    private static final String BLACKLIST_PREFIX = "jwt:bl:";
    private static final String CLAIM_EMAIL    = "email";
    private static final String CLAIM_PROVIDER = "provider";

    private final KeyPair             jwtKeyPair;
    private final JwtProperties       jwtProperties;
    private final StringRedisTemplate redisTemplate;
    private final JwtParser           jwtParser;
    private final map.service.user.domain.user.repository.UserRepository users;
    private final map.service.user.domain.user.repository.RefreshTokenRepository refreshTokens;

    public JwtService(KeyPair jwtKeyPair, JwtProperties jwtProperties,
                      @Qualifier("blacklistRedisTemplate") StringRedisTemplate redisTemplate,
                      map.service.user.domain.user.repository.UserRepository users,
                      map.service.user.domain.user.repository.RefreshTokenRepository refreshTokens) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.jwtKeyPair    = jwtKeyPair;
        this.jwtProperties = jwtProperties;
        this.redisTemplate = redisTemplate;
        this.jwtParser     = Jwts.parser()
                .verifyWith((RSAPublicKey) jwtKeyPair.getPublic())
                .build();
    }

    // ── Access Token ─────────────────────────────────────────────────────────

    public String generateAccessToken(User user) {
        return generateAccessToken(user, null);
    }

    public String generateAccessToken(User user, String sessionId) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + jwtProperties.getAccessTokenExpirySeconds() * 1000L);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim("sid", sessionId)
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_PROVIDER, user.getAuthProvider().name())
                .issuedAt(now)
                .expiration(exp)
                .signWith(jwtKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    /** 이미 열린 소켓에서 만료를 봐주는 폭. 토큰을 새로 받아 다시 붙는 왕복을 덮을 만큼만 둔다. */
    private static final java.time.Duration EXPIRY_GRACE = java.time.Duration.ofMinutes(5);

    /** 검증 후 Claims 반환. 유효하지 않으면 CustomException 던짐. */
    public Claims validateAccessToken(String token) {
        Claims claims = parseOrThrow(token);
        requireLiveSession(claims);
        return claims;
    }

    /**
     * 이미 열린 소켓으로 계속 내보내도 되는지 판정한다. 만료만 눈감고 나머지는 그대로 본다.
     *
     * 소켓은 붙을 때 한 번 인증하고 몇 시간이고 열려 있는데, 접근 토큰 수명은 그보다 짧다.
     * 만료를 그대로 거절하면 수명이 지난 순간부터 받는 쪽은 아무 말도 못 받으면서 연결은
     * 멀쩡해 보인다 — 끊기지 않으니 다시 붙지도 않는다. 그래서 전달에 한해 만료를 잠시
     * 봐준다. 대신 로그아웃·폐기는 만료와 무관하게 그대로 막고, 봐주는 창도 GRACE 로 닫는다.
     *
     * 서명은 만료 판정보다 먼저 검증되므로 예외에서 꺼낸 클레임도 위조되지 않았다.
     */
    public Claims validateAccessTokenTolerantOfExpiry(String token) {
        Claims claims;
        boolean expired = false;
        try {
            claims = jwtParser.parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            claims = e.getClaims();
            expired = true;
        } catch (JwtException | IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }
        if (expired) {
            java.util.Date exp = claims.getExpiration();
            if (exp == null || System.currentTimeMillis() > exp.getTime() + EXPIRY_GRACE.toMillis()) {
                throw new CustomException(ErrorCode.EXPIRED_TOKEN);
            }
        }
        requireLiveSession(claims);
        return claims;
    }

    /** 계정 존재·세션 폐기·개별 토큰 폐기. 만료 여부와 무관하게 늘 본다. */
    private void requireLiveSession(Claims claims) {
        Long userId = extractUserId(claims);
        if (!users.existsById(userId)) throw new CustomException(ErrorCode.INVALID_TOKEN);
        String session = claims.get("sid", String.class);
        if (session != null && !refreshTokens.existsBySessionIdAndRevokedAtIsNullAndExpiresAtAfter(
                session, java.time.OffsetDateTime.now())) throw new CustomException(ErrorCode.BLACKLISTED_TOKEN);
        if (isBlacklisted(claims.getId())) {
            throw new CustomException(ErrorCode.BLACKLISTED_TOKEN);
        }
    }

    public Long extractUserId(Claims claims) {
        try { return Long.parseLong(claims.getSubject()); }
        catch (NumberFormatException e) { throw new CustomException(ErrorCode.INVALID_TOKEN); }
    }

    /** 로그아웃 시 access token을 Redis blacklist에 등록 (남은 유효시간 동안) */
    public void blacklistAccessToken(String token) {
        try {
            Claims claims = parseOrThrow(token);
            long remainingMs = claims.getExpiration().getTime() - System.currentTimeMillis();
            if (remainingMs > 0) {
                redisTemplate.opsForValue()
                        .set(BLACKLIST_PREFIX + claims.getId(), "1", Duration.ofMillis(remainingMs));
            }
        } catch (CustomException ignored) {
            // 이미 만료된 토큰은 blacklist 불필요
        }
    }

    // ── Refresh Token ────────────────────────────────────────────────────────

    /** raw refresh token = UUID 문자열 (클라이언트에 전달) */
    public String generateRawRefreshToken() {
        return UUID.randomUUID().toString();
    }

    public long getRefreshTokenExpirySeconds() {
        return jwtProperties.getRefreshTokenExpirySeconds();
    }

    public long getAccessTokenExpirySeconds() {
        return jwtProperties.getAccessTokenExpirySeconds();
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private Claims parseOrThrow(String token) {
        try {
            return jwtParser.parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            throw new CustomException(ErrorCode.EXPIRED_TOKEN);
        } catch (JwtException | IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }
    }

    private boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti));
    }
}

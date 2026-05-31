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

    public JwtService(KeyPair jwtKeyPair, JwtProperties jwtProperties, StringRedisTemplate redisTemplate) {
        this.jwtKeyPair    = jwtKeyPair;
        this.jwtProperties = jwtProperties;
        this.redisTemplate = redisTemplate;
        this.jwtParser     = Jwts.parser()
                .verifyWith((RSAPublicKey) jwtKeyPair.getPublic())
                .build();
    }

    // ── Access Token ─────────────────────────────────────────────────────────

    public String generateAccessToken(User user) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + jwtProperties.getAccessTokenExpirySeconds() * 1000L);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_PROVIDER, user.getAuthProvider().name())
                .issuedAt(now)
                .expiration(exp)
                .signWith(jwtKeyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    /** 검증 후 Claims 반환. 유효하지 않으면 CustomException 던짐. */
    public Claims validateAccessToken(String token) {
        Claims claims = parseOrThrow(token);
        if (isBlacklisted(claims.getId())) {
            throw new CustomException(ErrorCode.BLACKLISTED_TOKEN);
        }
        return claims;
    }

    public Long extractUserId(Claims claims) {
        return Long.parseLong(claims.getSubject());
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

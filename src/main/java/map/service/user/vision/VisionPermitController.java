package map.service.user.vision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.List;
import map.service.user.domain.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** YOLO must obtain a consumed permit immediately before inference, using the end-user JWT. */
@RestController
@RequestMapping("/api/v1/vision")
public class VisionPermitController {
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            local n = tonumber(redis.call('GET', KEYS[1]) or '0')
            if n >= tonumber(ARGV[1]) then return -1 end
            n = redis.call('INCR', KEYS[1])
            if n == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
            return n
            """, Long.class);
    private final StringRedisTemplate redis;
    private final UserRepository users;
    private final String internalToken;
    private final int limit;

    public VisionPermitController(@Qualifier("blacklistRedisTemplate") StringRedisTemplate redis,
                                  UserRepository users,
                                  @Value("${vision.internal-token:}") String internalToken,
                                  @Value("${vision.daily-limit:60}") int limit) {
        this.redis = redis; this.users = users; this.internalToken = internalToken; this.limit = limit;
    }
    public record Request(boolean consume) {}
    public record Permit(@com.fasterxml.jackson.annotation.JsonProperty("user_id") long userId,
                         long remaining,
                         @com.fasterxml.jackson.annotation.JsonProperty("reset_at") Instant resetAt) {}

    @ExceptionHandler(ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Void> unavailable(ResponseStatusException error) {
        return org.springframework.http.ResponseEntity.status(error.getStatusCode()).build();
    }

    @PostMapping("/permit")
    public Permit permit(@AuthenticationPrincipal Long userId,
                         @RequestHeader(value = "X-Internal-Token", required = false) String token,
                         @RequestBody Request request) {
        if (internalToken == null || internalToken.isBlank() || limit <= 0)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "vision is not configured");
        if (token == null || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (userId == null || !users.existsById(userId))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Instant now = Instant.now();
        LocalDate date = now.atOffset(ZoneOffset.UTC).toLocalDate();
        Instant reset = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        String key = "vision:daily:" + userId + ":" + date;
        long used;
        try {
            if (request.consume()) {
                Long result = redis.execute(CONSUME, List.of(key), Integer.toString(limit),
                        Long.toString(Math.max(1, Duration.between(now, reset).toSeconds())));
                if (result == null) throw new IllegalStateException("quota unavailable");
                used = result;
            } else {
                String value = redis.opsForValue().get(key);
                used = value == null ? 0 : Long.parseLong(value);
            }
        } catch (RuntimeException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "vision quota unavailable");
        }
        if (used < 0 || (request.consume() && used > limit))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "vision daily limit reached");
        return new Permit(userId, Math.max(0, limit - used), reset);
    }
}

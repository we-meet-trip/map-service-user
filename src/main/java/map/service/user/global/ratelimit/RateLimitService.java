package map.service.user.global.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final String PREFIX = "rate:";

    // INCR와 EXPIRE를 원자적으로 처리 — 두 명령 사이 race condition 방지
    static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;
    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setScriptText(
            "local c = redis.call('INCR', KEYS[1])\n" +
            "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end\n" +
            "return c"
        );
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redisTemplate;

    /** @return true = 허용, false = 초과. Redis 장애 시 fail-open (허용) */
    public boolean isAllowed(String key, int limit, Duration window) {
        try {
            String redisKey = PREFIX + key;
            Long count = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(redisKey),
                    String.valueOf(window.getSeconds()));
            return count == null || count <= limit;
        } catch (Exception e) {
            log.warn("Rate limit Redis error — fail-open: {}", e.getMessage());
            return true;
        }
    }
}

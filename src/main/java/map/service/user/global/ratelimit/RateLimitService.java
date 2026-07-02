package map.service.user.global.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Slf4j
@Service
public class RateLimitService {

    // 인증 시도 카운터 키 접두어. 라우트/IP 를 뒤에 덧붙여 버킷을 구분한다.
    private static final String PREFIX = "rate-limit:auth:";

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

    public RateLimitService(@Qualifier("rateLimitRedisTemplate") StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

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

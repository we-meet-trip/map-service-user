package map.service.user.global.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RateLimitService 단위 테스트")
class RateLimitServiceTest {

    @Mock private StringRedisTemplate redisTemplate;

    @InjectMocks
    private RateLimitService rateLimitService;

    @Test
    @DisplayName("첫 요청 — 허용")
    void isAllowed_firstRequest_allows() {
        when(redisTemplate.execute(eq(RateLimitService.RATE_LIMIT_SCRIPT), anyList(), any(String.class)))
                .thenReturn(1L);

        boolean result = rateLimitService.isAllowed("login:127.0.0.1", 10, Duration.ofSeconds(60));

        assertThat(result).isTrue();
        verify(redisTemplate).execute(eq(RateLimitService.RATE_LIMIT_SCRIPT), eq(List.of("rate:login:127.0.0.1")), eq("60"));
    }

    @Test
    @DisplayName("한도 이하 요청 — 허용")
    void isAllowed_underLimit_allows() {
        when(redisTemplate.execute(eq(RateLimitService.RATE_LIMIT_SCRIPT), anyList(), any(String.class)))
                .thenReturn(5L);

        boolean result = rateLimitService.isAllowed("login:127.0.0.1", 10, Duration.ofSeconds(60));

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("한도 정확히 도달 — 허용")
    void isAllowed_exactLimit_allows() {
        when(redisTemplate.execute(eq(RateLimitService.RATE_LIMIT_SCRIPT), anyList(), any(String.class)))
                .thenReturn(10L);

        boolean result = rateLimitService.isAllowed("login:127.0.0.1", 10, Duration.ofSeconds(60));

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("한도 초과 — 차단")
    void isAllowed_overLimit_blocks() {
        when(redisTemplate.execute(eq(RateLimitService.RATE_LIMIT_SCRIPT), anyList(), any(String.class)))
                .thenReturn(11L);

        boolean result = rateLimitService.isAllowed("login:127.0.0.1", 10, Duration.ofSeconds(60));

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Redis 반환값 null — 허용 (fail-open: Redis 장애 시 서비스 중단 방지)")
    void isAllowed_nullFromRedis_allowsFailOpen() {
        when(redisTemplate.execute(eq(RateLimitService.RATE_LIMIT_SCRIPT), anyList(), any(String.class)))
                .thenReturn(null);

        boolean result = rateLimitService.isAllowed("login:127.0.0.1", 10, Duration.ofSeconds(60));

        assertThat(result).isTrue();
    }
}

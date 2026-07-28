package map.service.user.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ResearchLimitServiceTest — 재추천 일일 한도 카운터 단위 테스트
 *
 * 한도(3) 이내 허용 / 초과 차단, Redis null·예외 시 fail-open,
 * 다음 KST 자정 epoch 계산의 정확성을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResearchLimitService 단위 테스트")
class ResearchLimitServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Mock private StringRedisTemplate redisTemplate;

    private ResearchLimitService service;

    @BeforeEach
    void setUp() {
        service = new ResearchLimitService(redisTemplate, 3);
    }

    private void stubCount(Long count) {
        when(redisTemplate.execute(
                eq(ResearchLimitService.RESEARCH_LIMIT_SCRIPT), anyList(), any(String.class)))
                .thenReturn(count);
    }

    @Test
    @DisplayName("1~3회차 — 허용")
    void withinLimitAllowed() {
        stubCount(1L);
        assertThat(service.tryConsume("sched:1")).isTrue();
        stubCount(2L);
        assertThat(service.tryConsume("sched:1")).isTrue();
        stubCount(3L);
        assertThat(service.tryConsume("sched:1")).isTrue();
    }

    @Test
    @DisplayName("4회차 — 차단")
    void overLimitBlocked() {
        stubCount(4L);
        assertThat(service.tryConsume("sched:1")).isFalse();
    }

    @Test
    @DisplayName("Redis 반환 null — fail-open(허용)")
    void nullCountFailOpen() {
        stubCount(null);
        assertThat(service.tryConsume("sched:1")).isTrue();
    }

    @Test
    @DisplayName("Redis 예외 — fail-open(허용)")
    void redisExceptionFailOpen() {
        when(redisTemplate.execute(
                eq(ResearchLimitService.RESEARCH_LIMIT_SCRIPT), anyList(), any(String.class)))
                .thenThrow(new RuntimeException("redis down"));
        assertThat(service.tryConsume("sched:1")).isTrue();
    }

    @Test
    @DisplayName("키에 PREFIX 를 붙여 스크립트에 전달")
    void prefixesKey() {
        stubCount(1L);
        service.tryConsume("sched:42");
        verify(redisTemplate).execute(
                eq(ResearchLimitService.RESEARCH_LIMIT_SCRIPT),
                eq(List.of("recommend:research:sched:42")),
                any(String.class));
    }

    @Test
    @DisplayName("nextKstMidnightEpoch — 오후 시각의 다음 KST 자정 계산")
    void nextKstMidnightFromAfternoon() {
        // 2026-07-07T14:00 KST (=05:00Z) → 다음 자정 2026-07-08T00:00 KST
        Clock fixed = Clock.fixed(Instant.parse("2026-07-07T05:00:00Z"), KST);
        long expected = ZonedDateTime.of(2026, 7, 8, 0, 0, 0, 0, KST).toEpochSecond();
        assertThat(service.nextKstMidnightEpoch(fixed)).isEqualTo(expected);
    }

    @Test
    @DisplayName("nextKstMidnightEpoch — 자정 직전 시각도 당일 다음 자정")
    void nextKstMidnightJustBeforeMidnight() {
        // 2026-07-07T23:59 KST (=14:59Z) → 다음 자정 2026-07-08T00:00 KST
        Clock fixed = Clock.fixed(Instant.parse("2026-07-07T14:59:00Z"), KST);
        long expected = ZonedDateTime.of(2026, 7, 8, 0, 0, 0, 0, KST).toEpochSecond();
        assertThat(service.nextKstMidnightEpoch(fixed)).isEqualTo(expected);
    }
}

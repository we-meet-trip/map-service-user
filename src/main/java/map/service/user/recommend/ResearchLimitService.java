package map.service.user.recommend;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * ResearchLimitService — Mode1 재추천 일일 한도 카운터
 *
 * 일정(scheduleId) 단위로 재추천(research) 횟수를 KST 자정까지 카운트하고
 * 한도(기본 3회)를 초과하면 소비를 거부한다. RateLimitService 의 INCR+만료
 * 원자 처리 패턴을 그대로 따르되, TTL 을 고정 창(EXPIRE)이 아니라 다음 KST
 * 자정(EXPIREAT)으로 설정하여 "매일 자정 리셋" 의미를 정확히 구현한다.
 *
 * redis: @Qualifier("countersRedisTemplate") StringRedisTemplate. 카운터 전용 연결(DB3).
 * limit: recommend.research-daily-limit 프로퍼티(기본 3). 이 값 이하면 허용.
 *
 * Redis 장애(예외) 또는 null 카운트는 fail-open(허용)으로 처리한다 — 카운터
 * 장애가 재추천 자체를 막아서는 안 된다.
 */
@Service
public class ResearchLimitService {

    private static final Logger log = LoggerFactory.getLogger(ResearchLimitService.class);

    /** 재추천 카운터 키 접두어. 뒤에 sched:{id} 또는 job:{id} 버킷을 덧붙인다. */
    private static final String PREFIX = "recommend:research:";

    /** KST 기준 자정 계산용 존. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // INCR 후 첫 증가 시에만 EXPIREAT(다음 KST 자정)로 만료 시각을 고정한다.
    // 두 명령 사이 race condition 을 Lua 원자 실행으로 방지한다.
    static final DefaultRedisScript<Long> RESEARCH_LIMIT_SCRIPT;
    static {
        RESEARCH_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RESEARCH_LIMIT_SCRIPT.setScriptText(
                "local c = redis.call('INCR', KEYS[1])\n"
                + "if c == 1 then redis.call('EXPIREAT', KEYS[1], ARGV[1]) end\n"
                + "return c"
        );
        RESEARCH_LIMIT_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;
    private final int limit;

    public ResearchLimitService(
            @Qualifier("countersRedisTemplate") StringRedisTemplate redis,
            @Value("${recommend.research-daily-limit:3}") int limit
    ) {
        this.redis = redis;
        this.limit = limit;
    }

    /**
     * key 버킷의 오늘(자정까지) 소비 횟수를 1 증가시키고 한도 이내인지 반환.
     *
     * 최초 증가 시 다음 KST 자정에 만료되도록 EXPIREAT 을 건다. 반환 카운트가
     * limit 이하이면 true(허용), 초과이면 false(차단). Redis 예외/ null 카운트는
     * fail-open(true) 으로 처리한다.
     *
     * key: 카운터 버킷 식별자(예: "sched:42", "job:{uuid}"). PREFIX 가 앞에 붙는다.
     */
    public boolean tryConsume(String key) {
        try {
            long midnight = nextKstMidnightEpoch(Clock.system(KST));
            Long count = redis.execute(
                    RESEARCH_LIMIT_SCRIPT,
                    List.of(PREFIX + key),
                    String.valueOf(midnight));
            return count == null || count <= limit;
        } catch (Exception e) {
            log.warn("research limit Redis error — fail-open: {}", e.getMessage());
            return true;
        }
    }

    /**
     * clock 기준 "다음 KST 자정" 의 epoch seconds 를 계산한다(EXPIREAT 인자용).
     *
     * clock 의 순간을 KST 로 본 뒤 그 날짜의 다음 날 00:00(KST) 를 epoch 초로 변환한다.
     * 테스트에서 고정 Clock 을 주입해 결정적으로 검증할 수 있도록 분리했다.
     */
    long nextKstMidnightEpoch(Clock clock) {
        LocalDate today = clock.instant().atZone(KST).toLocalDate();
        return today.plusDays(1).atStartOfDay(KST).toEpochSecond();
    }
}

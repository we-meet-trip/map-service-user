package map.service.user.recommend;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import map.service.user.recommend.dto.RecommendRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RecommendCacheKey — RecommendRequest 를 재사용 캐시용 정규화 해시로 변환
 *
 * province/city/theme(정렬)/mobility/date(dateStart·dateEnd 완전일치, timeStart·timeEnd
 * 반올림)/budget(내림)을 하나의 문자열로 정규화한 뒤 SHA-256 해시(hex)로 변환한다. Redis
 * 상태를 갖지 않는 순수 함수이므로 Spring 컨텍스트 없이도 단위 테스트 가능하다.
 *
 * budgetRoundStep: budget 근사 매칭용 내림(floor) 단위(원). 예산 초과 추천 방지를 위해
 *                  올림 없이 항상 내림한다. recommend.cache-budget-round-step 프로퍼티,
 *                  기본 50000. 0 이하로 설정되면 나눗셈 오류를 막기 위해 기본값으로
 *                  대체하고 경고 로그를 남긴다.
 * timeRoundStepMinutes: timeStart/timeEnd 근사 매칭용 반올림 단위(분).
 *                  recommend.cache-time-round-step-minutes 프로퍼티, 기본 60(1시간).
 *                  0 이하로 설정되면 budgetRoundStep 과 동일하게 기본값으로 대체한다.
 */
@Component
public class RecommendCacheKey {

    private static final Logger log = LoggerFactory.getLogger(RecommendCacheKey.class);

    private static final String FIELD_SEPARATOR = "|";
    private static final String THEME_SEPARATOR = ",";
    private static final String NONE_SENTINEL = "none";
    private static final long DEFAULT_BUDGET_ROUND_STEP = 50000L;
    private static final long DEFAULT_TIME_ROUND_STEP_MINUTES = 60L;

    private final long budgetRoundStep;
    private final long timeRoundStepMinutes;

    public RecommendCacheKey(
            @Value("${recommend.cache-budget-round-step:50000}") long budgetRoundStep,
            @Value("${recommend.cache-time-round-step-minutes:60}") long timeRoundStepMinutes
    ) {
        if (budgetRoundStep > 0) {
            this.budgetRoundStep = budgetRoundStep;
        } else {
            log.warn("recommend.cache-budget-round-step must be positive but was {}, using default {}",
                    budgetRoundStep, DEFAULT_BUDGET_ROUND_STEP);
            this.budgetRoundStep = DEFAULT_BUDGET_ROUND_STEP;
        }
        if (timeRoundStepMinutes > 0) {
            this.timeRoundStepMinutes = timeRoundStepMinutes;
        } else {
            log.warn("recommend.cache-time-round-step-minutes must be positive but was {}, using default {}",
                    timeRoundStepMinutes, DEFAULT_TIME_ROUND_STEP_MINUTES);
            this.timeRoundStepMinutes = DEFAULT_TIME_ROUND_STEP_MINUTES;
        }
    }

    /**
     * request 를 정규화한 뒤 SHA-256 hex 해시로 반환한다.
     *
     * request: 해시 대상 RecommendRequest.
     */
    public String hash(RecommendRequest request) {
        return sha256Hex(canonicalize(request));
    }

    private String canonicalize(RecommendRequest request) {
        List<String> theme = new ArrayList<>(
                request.theme() == null ? List.of() : request.theme());
        theme.sort(String::compareTo);

        String mobility = request.mobility() == null
                ? NONE_SENTINEL
                : request.mobility().value();

        String budget = request.budget() == null
                ? NONE_SENTINEL
                : String.valueOf(roundBudget(request.budget()));

        return String.join(FIELD_SEPARATOR,
                request.province(),
                request.city(),
                String.join(THEME_SEPARATOR, theme),
                mobility,
                budget,
                request.date().dateStart().toString(),
                request.date().dateEnd().toString(),
                String.valueOf(roundTimeMinutes(request.date().timeStart())),
                String.valueOf(roundTimeMinutes(request.date().timeEnd())));
    }

    private long roundBudget(int budget) {
        return Math.floorDiv((long) budget, budgetRoundStep) * budgetRoundStep;
    }

    private long roundTimeMinutes(LocalTime time) {
        long totalMinutes = time.getHour() * 60L + time.getMinute();
        return Math.round(totalMinutes / (double) timeRoundStepMinutes) * timeRoundStepMinutes;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

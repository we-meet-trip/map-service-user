package map.service.user.places;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import map.service.user.places.dto.ReviewItem;
import map.service.user.places.dto.ReviewSearchResponse;
import map.service.user.places.dto.ReviewSummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * ReviewSummaryService — 장소 블로그 요약 조립
 *
 * 장소를 누를 때마다 모델을 부르지 않도록, 장소별 요약을 캐시에 담아 재사용
 * 한다. 같은 장소를 여러 사용자가 눌러도 하루에 한 번만 요약한다.
 *
 * 캐시는 요약 결과만 담는다. 블로그 목록은 hub 가 이미 짧게 캐싱하고, 화면이
 * 더보기로 구간을 옮겨 가며 보므로 여기서 또 담을 이유가 없다.
 *
 * 실패는 담지 않는다. 한도 초과나 일시 장애로 만들어진 빈 요약을 담아 두면
 * 하루 동안 그 장소는 계속 요약이 없는 상태로 굳는다.
 */
@Service
public class ReviewSummaryService {

    private static final Logger log =
            LoggerFactory.getLogger(ReviewSummaryService.class);

    /** 캐시 키 접두사. 형식이 바뀌면 뒤의 판을 올려 옛 값과 섞이지 않게 한다. */
    private static final String KEY_PREFIX = "reviews:summary:v1:";

    /** 요약 근거로 모을 블로그 글 수. agent 요청 상한과 맞춘다. */
    private static final int SOURCE_COUNT = 7;

    /**
     * 캐시에 담긴 두 줄을 잇는 구분자.
     *
     * 단위 구분자(제어문자)를 쓴다. 요약 문장에는 나올 수 없는 문자라 본문과
     * 섞이지 않는다 — 쉼표나 줄바꿈을 쓰면 요약 안의 같은 문자가 경계로
     * 오인돼 줄이 쪼개진다. 요약을 만드는 쪽이 제어문자를 거부하므로 이
     * 문자가 요약에 실려 올 일도 없다.
     */
    private static final String JOIN = "\u001F";

    private final ReviewSearchClient reviewClient;
    private final AgentSummaryClient summaryClient;
    private final StringRedisTemplate redis;
    private final Duration ttl;

    public ReviewSummaryService(
            ReviewSearchClient reviewClient,
            AgentSummaryClient summaryClient,
            @Qualifier("cacheRedisTemplate") StringRedisTemplate redis,
            @Value("${reviews.summary-ttl-seconds:86400}") long ttlSeconds
    ) {
        this.reviewClient = reviewClient;
        this.summaryClient = summaryClient;
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /**
     * 장소명으로 두 줄 요약을 얻는다.
     *
     * query: 장소명. 캐시 키는 앞뒤 공백을 없애고 소문자로 맞춘 값을 해시해
     *        만든다 — 같은 장소를 조금 다르게 적어 보내도 한 번만 요약한다.
     *
     * 반환: 요약과 근거 글 수. 근거를 못 구하거나 요약에 실패하면 빈 목록.
     */
    public ReviewSummaryResponse summarize(String query) {
        String key = cacheKey(query);
        Cached cached = readCache(key);
        if (cached != null) {
            return new ReviewSummaryResponse(
                    query, cached.bullets(), cached.sourceCount());
        }

        List<ReviewItem> sources = fetchSources(query);
        if (sources.isEmpty()) {
            return new ReviewSummaryResponse(query, List.of(), 0);
        }
        List<String> bullets = summaryClient.summarize(query, sources);
        if (!bullets.isEmpty()) {
            writeCache(key, bullets, sources.size());
        }
        return new ReviewSummaryResponse(query, bullets, sources.size());
    }

    /** 캐시에 담는 값 — 요약 줄과 그 근거가 된 글 수. */
    private record Cached(List<String> bullets, int sourceCount) {
    }

    /** 요약 근거가 될 블로그 글을 모은다. 조회 실패는 근거 없음으로 흡수한다. */
    private List<ReviewItem> fetchSources(String query) {
        try {
            ReviewSearchResponse res =
                    reviewClient.search(query, SOURCE_COUNT, 1, "sim");
            if (res == null || res.reviews() == null) {
                return List.of();
            }
            return res.reviews();
        } catch (RuntimeException e) {
            log.warn("review sources fetch failed reason={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 캐시에서 요약을 읽는다. 연결 장애는 캐시 미스와 같게 다룬다.
     *
     * 첫 칸이 근거 글 수, 나머지가 요약 줄이다. 근거 수를 함께 담지 않으면
     * 캐시에 걸린 요청만 근거 수가 요약 줄 수로 바뀌어, 같은 질의가 호출마다
     * 다른 값을 돌려준다.
     */
    private Cached readCache(String key) {
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            String[] parts = raw.split(JOIN);
            if (parts.length < 2) {
                return null;
            }
            int sourceCount;
            try {
                sourceCount = Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                // 형식이 다른 옛 값은 미스로 다뤄 다시 만든다.
                return null;
            }
            List<String> bullets =
                    List.of(parts).subList(1, parts.length);
            return new Cached(bullets, sourceCount);
        } catch (RuntimeException e) {
            log.warn("summary cache read failed reason={}", e.getMessage());
            return null;
        }
    }

    /** 요약을 캐시에 담는다. 연결 장애는 삼킨다 — 담지 못해도 응답은 유효하다. */
    private void writeCache(String key, List<String> bullets, int sources) {
        try {
            String payload =
                    sources + JOIN + String.join(JOIN, bullets);
            redis.opsForValue().set(key, payload, ttl);
        } catch (RuntimeException e) {
            log.warn("summary cache write failed reason={}", e.getMessage());
        }
    }

    /** 장소명을 정규화해 해시한 캐시 키. */
    private static String cacheKey(String query) {
        String normalized = query.strip().toLowerCase();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed =
                    digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // 표준 JDK 에 항상 있는 알고리즘이라 실제로는 도달하지 않는다.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

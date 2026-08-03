package map.service.user.places;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
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
 * 일정이 만들어진 직후에는 그 일정의 장소를 미리 요약해 둔다(prewarm). 장소를
 * 누르는 시점에 만들면 그 자리에서 모델 응답을 기다려야 하는데, 일정에 담긴
 * 장소는 대부분 한 번씩 눌러 보기 때문이다. 미리 만든 요약도 누를 때 만드는
 * 요약과 **같은 키·같은 형식**으로 담기므로 읽는 쪽은 구분하지 않는다.
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
    private final Executor prewarmExecutor;
    private final boolean prewarmEnabled;
    private final int prewarmMaxPlaces;
    private final int prewarmBatchSize;

    public ReviewSummaryService(
            ReviewSearchClient reviewClient,
            AgentSummaryClient summaryClient,
            @Qualifier("cacheRedisTemplate") StringRedisTemplate redis,
            @Value("${reviews.summary-ttl-seconds:86400}") long ttlSeconds,
            @Qualifier("reviewSummaryPrewarmExecutor") Executor prewarmExecutor,
            @Value("${reviews.prewarm-enabled:true}") boolean prewarmEnabled,
            @Value("${reviews.prewarm-max-places:7}") int prewarmMaxPlaces,
            @Value("${reviews.prewarm-batch-size:7}") int prewarmBatchSize
    ) {
        this.reviewClient = reviewClient;
        this.summaryClient = summaryClient;
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.prewarmExecutor = prewarmExecutor;
        this.prewarmEnabled = prewarmEnabled;
        this.prewarmMaxPlaces = prewarmMaxPlaces;
        this.prewarmBatchSize = prewarmBatchSize;
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

    /**
     * 일정에 담긴 장소들의 요약을 미리 만들어 캐시에 담는다.
     *
     * 호출 스레드를 막지 않고 즉시 돌아간다 — 일정 응답을 기다리는 사용자가
     * 이 작업 때문에 늦어지면 안 된다.
     *
     * places: 방문 순서대로의 장소. 앞에서부터 상한만큼만 다룬다. 뒤쪽
     *         장소는 눌렀을 때 그 자리에서 만들어지므로 화면은 정상이다.
     */
    public void prewarm(List<PrewarmPlace> places) {
        if (!prewarmEnabled || places == null || places.isEmpty()) {
            return;
        }
        List<PrewarmPlace> targets = dedupe(places);
        if (targets.isEmpty()) {
            return;
        }
        for (int from = 0; from < targets.size(); from += prewarmBatchSize) {
            int to = Math.min(from + prewarmBatchSize, targets.size());
            List<PrewarmPlace> chunk = List.copyOf(targets.subList(from, to));
            try {
                prewarmExecutor.execute(() -> prewarmNow(chunk));
            } catch (RuntimeException e) {
                // 대기열이 가득 차 거부되는 경우까지 포함해 흡수한다.
                log.warn("summary prewarm submit failed reason={}",
                        e.getMessage());
            }
        }
    }

    /**
     * 이미 담긴 장소를 빼고, 캐시 키 기준 중복을 제거해 상한까지 남긴다.
     *
     * 이름이 조금 달라도 같은 키로 접히는 경우가 있어 원문이 아니라 키로
     * 견준다. 이미 있는지는 존재 확인이 아니라 **읽기**로 판단한다 —
     * 형식이 다른 옛 값을 미스로 다루는 규칙을 한 곳에만 두기 위함이다.
     */
    private List<PrewarmPlace> dedupe(List<PrewarmPlace> places) {
        Set<String> seen = new HashSet<>();
        List<PrewarmPlace> out = new ArrayList<>();
        for (PrewarmPlace place : places) {
            if (out.size() >= prewarmMaxPlaces) {
                break;
            }
            if (place == null || place.name() == null
                    || place.name().isBlank()) {
                continue;
            }
            String key = cacheKey(place.name());
            if (!seen.add(key)) {
                continue;
            }
            if (readCache(key) != null) {
                continue;
            }
            out.add(place);
        }
        return out;
    }

    /**
     * 실행기 스레드에서 도는 본체 — 어떤 실패도 밖으로 내보내지 않는다.
     *
     * 근거를 모아 한 번에 요약을 맡기고, 두 줄을 받은 장소만 담는다.
     * 요약을 만드는 사이 사용자가 그 장소를 누르면 요약이 한 번 더
     * 만들어질 수 있다. 그대로 둔다 — 진행 중임을 표시해 두면 그 순간에
     * 누른 사용자는 실제 요약 대신 빈 화면을 확정으로 받게 되어, 호출
     * 한 번을 아끼려고 화면의 답을 버리는 거래가 된다.
     */
    private void prewarmNow(List<PrewarmPlace> chunk) {
        try {
            List<AgentSummaryClient.BatchPlace> batch = new ArrayList<>();
            List<String> names = new ArrayList<>();
            List<Integer> sourceCounts = new ArrayList<>();
            for (PrewarmPlace place : chunk) {
                List<ReviewItem> sources = fetchSources(place.name());
                if (sources.isEmpty()) {
                    continue;
                }
                names.add(place.name());
                sourceCounts.add(sources.size());
                batch.add(new AgentSummaryClient.BatchPlace(
                        place.name(), place.category(), sources));
            }
            if (batch.isEmpty()) {
                return;
            }
            Map<Integer, List<String>> summarized =
                    summaryClient.summarizeBatch(batch);
            int written = 0;
            for (Map.Entry<Integer, List<String>> e : summarized.entrySet()) {
                int index = e.getKey();
                if (index < 0 || index >= names.size()) {
                    continue;
                }
                List<String> bullets = e.getValue();
                if (bullets == null || bullets.isEmpty()) {
                    continue;
                }
                writeCache(
                        cacheKey(names.get(index)),
                        bullets,
                        sourceCounts.get(index));
                written++;
            }
            log.info("summary prewarm done places={} written={}",
                    batch.size(), written);
        } catch (RuntimeException e) {
            log.warn("summary prewarm failed reason={}", e.getMessage());
        }
    }

    /** 미리 요약할 장소 한 건 — 이름과 분류만 있으면 된다. */
    public record PrewarmPlace(String name, String category) {
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

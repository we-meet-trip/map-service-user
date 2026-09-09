package map.service.user.recommend;

import java.time.Duration;
import java.util.Optional;
import java.util.List;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import map.service.user.global.crypto.PayloadCipher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * ReuseCacheStore — 추천 재사용 캐시의 Redis 기반 저장소
 *
 * 동일 조건 추천 요청의 결과를 정규화 해시(hash) 기준으로 캐싱한다. 3종 키를 관리한다:
 * - recommend:cache:{hash}      : 완성된 draft JSON(캐시 본체, TTL cacheTtl)
 * - recommend:cache-link:{jobId}: 진행 중인 job 이 어느 hash 에 속하는지 기억하는
 *                                  연결고리(1회성, TTL linkTtl)
 * - recommend:cache-hits:{hash} : 히트 횟수 카운터(TTL cacheTtl, 최초 생성 시 설정되고
 *                                  캐시 본체 갱신 시 renewHitsTtl 로 함께 연장됨)
 *
 * RecommendService(히트/미스 분기, 백그라운드 갱신)와
 * RecommendJobsConsumer(완료 이벤트에서 캐시 본체 갱신)가 사용한다.
 *
 * redis: @Qualifier("cacheRedisTemplate") StringRedisTemplate. 캐시 전용 Redis 연결.
 * cacheTtl: redis.cache-ttl-seconds 프로퍼티(기본 604800=7일) 기반 Duration.
 * linkTtl: redis.cache-link-ttl-seconds 프로퍼티(기본 3600) 기반 Duration.
 */
@Component
public class ReuseCacheStore {

    private static final String CACHE_PREFIX = "recommend:cache:";
    private static final String LINK_PREFIX = "recommend:cache-link:";
    private static final String HITS_PREFIX = "recommend:cache-hits:";
    /** 같은 조건을 지금 누가 만들고 있는지 표시하는 자리. 한 명만 잡는다. */
    private static final String INFLIGHT_PREFIX = "recommend:inflight:";
    /** 남이 만드는 것을 기다리는 job 이 어느 조건을 기다리는지. */
    private static final String WAIT_PREFIX = "recommend:cache-wait:";

    private final StringRedisTemplate redis;
    private final Duration cacheTtl;
    private final Duration linkTtl;
    private final PayloadCipher payloadCipher;
    /**
     * 만드는 중 표시와 기다림 표시의 수명. 잡 처리 시간(최대 10분)보다 길게
     * 잡는다. 짧으면 아직 만들고 있는 중에 표시가 사라져 두 번째 요청이 다시
     * agent 를 불러, 막으려던 중복이 그대로 생긴다.
     */
    private final Duration inflightTtl;

    public ReuseCacheStore(
            @Qualifier("cacheRedisTemplate") StringRedisTemplate redis,
            @Value("${redis.cache-ttl-seconds:604800}") long cacheTtlSeconds,
            @Value("${redis.cache-link-ttl-seconds:3600}") long linkTtlSeconds,
            PayloadCipher payloadCipher,
            @Value("${redis.inflight-ttl-seconds:600}") long inflightTtlSeconds
    ) {
        this.redis = redis;
        this.inflightTtl = Duration.ofSeconds(inflightTtlSeconds);
        this.cacheTtl = Duration.ofSeconds(cacheTtlSeconds);
        this.linkTtl = Duration.ofSeconds(linkTtlSeconds);
        this.payloadCipher = payloadCipher;
    }

    /**
     * 캐시 본체를 묶어 둘 자리 이름. 이 캐시는 일곱 날을 살아 남으므로 저장소
     * 파일이 유출됐을 때 가장 오래된 본문까지 함께 나간다. 요청 조건 해시를
     * 묶어, 한 조건의 본문을 다른 조건의 키에 옮겨 넣어도 열리지 않게 한다.
     */
    private static String aadFor(String hash) {
        return PayloadCipher.aad("redis", CACHE_PREFIX, hash);
    }

    /**
     * hash 에 해당하는 캐시 본체(draft JSON) 조회. 없으면 Optional.empty.
     */
    public Optional<String> find(String hash) {
        return Optional.ofNullable(redis.opsForValue().get(cacheKey(hash)))
                .map(value -> payloadCipher.decrypt(value, aadFor(hash)));
    }

    /**
     * 이 조건을 만들 사람으로 나설 수 있으면 true.
     *
     * 같은 조건의 요청이 동시에 여러 개 들어오면 지금은 전부 agent 를 부른다.
     * 한 건이 LLM 을 두 번 쓰므로 열 명이 같은 곳을 동시에 누르면 스무 번이
     * 나간다. 하루 한도가 정해져 있는 자원이라 그 한 번이 크다.
     *
     * SET NX 는 명령 하나로 판정과 표시를 함께 한다. 먼저 잡은 하나만 true 를
     * 받고 나머지는 false 를 받아 결과를 기다린다.
     *
     * 시한을 두는 이유: 표시를 남긴 쪽이 죽으면 아무도 지우지 못한다. 잡
     * 처리 시간보다 넉넉히 길게 두어, 시한이 먼저 끝나 중복이 나가는 일이
     * 없게 한다.
     */
    public String claimProducer(String hash, String token) {
        return redis.execute(new DefaultRedisScript<>(
                "local current = redis.call('GET', KEYS[1]); if current then return current end; "
                + "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]); return ARGV[1]", String.class),
                List.of(inflightKey(hash)), token, Long.toString(inflightTtl.toSeconds()));
    }

    private void releaseProducer(String hash, String token) {
        redis.execute(new DefaultRedisScript<>(
                "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end; return 0", Long.class),
                List.of(inflightKey(hash)), token);
    }

    /** Keep the generation link until expiry so redelivery can repair a missed notification. */
    public void registerProducer(String jobId, String hash, String token) {
        redis.opsForValue().set("recommend:producer:" + jobId, hash + "|" + token, inflightTtl);
    }

    public void publishCompletion(String jobId) {
        completeProducer(jobId, jobId);
    }

    public void cancelProducer(String jobId) {
        completeProducer(jobId, "failed");
    }

    private void completeProducer(String jobId, String result) {
        String link = redis.opsForValue().get("recommend:producer:" + jobId);
        if (link == null) return;
        String[] parts = link.split("\\|", 2);
        if (parts.length != 2) return;
        redis.opsForValue().set("recommend:completion:" + parts[1], result, inflightTtl);
        releaseProducer(parts[0], parts[1]);
    }

    public void failProducer(String hash, String token) {
        redis.opsForValue().set("recommend:completion:" + token, "failed", inflightTtl);
        releaseProducer(hash, token);
    }

    public Optional<String> completion(String token) {
        return Optional.ofNullable(redis.opsForValue().get("recommend:completion:" + token));
    }

    /** A waiter follows one execution, never a later request with the same conditions. */
    public void markWaiting(String jobId, String token) {
        redis.opsForValue().set(waitKey(jobId), "v1:" + token, inflightTtl);
    }

    public void markLegacyWaiting(String jobId, String hash) {
        redis.opsForValue().set(waitKey(jobId), hash, inflightTtl);
    }

    /** 이 job 이 기다리는 조건. 기다리는 중이 아니면 empty. */
    public Optional<String> waitingHash(String jobId) {
        return Optional.ofNullable(redis.opsForValue().get(waitKey(jobId)));
    }

    /** 기다림이 끝났다. */
    public void clearWaiting(String jobId) {
        redis.delete(waitKey(jobId));
    }

    /**
     * hash 키에 draft JSON 을 cacheTtl 과 함께 저장(SET + EX). 기존 값 있으면 덮어쓴다
     * (신규 생성 및 백그라운드 통짜 갱신 양쪽에서 사용).
     */
    public void save(String hash, String payloadJson) {
        redis.opsForValue().set(
                cacheKey(hash), payloadCipher.encrypt(payloadJson, aadFor(hash)), cacheTtl);
    }

    /**
     * jobId 를 hash 에 연결(SET + EX linkTtl). 이 job 이 완료되면
     * RecommendJobsConsumer 가 consumeLink 로 이 연결을 찾아 캐시를 갱신한다.
     */
    public void linkJob(String jobId, String hash) {
        redis.opsForValue().set(linkKey(jobId), hash, linkTtl);
    }

    /**
     * jobId 에 연결된 hash 를 원자적으로 조회 후 삭제한다(GETDEL, 1회성 연결고리).
     * 연결이 없으면 Optional.empty.
     */
    public Optional<String> consumeLink(String jobId) {
        return Optional.ofNullable(redis.opsForValue().getAndDelete(linkKey(jobId)));
    }

    /**
     * hash 의 히트 카운터를 1 증가(INCR)시킨 뒤, 증가 후 값을 반환한다. TTL 은 카운터가
     * 최초 생성되는 시점(count == 1)에만 cacheTtl 로 설정한다 — 매 히트마다 EXPIRE 를
     * 반복 호출하는 대신, 캐시 본체가 실제로 갱신될 때 renewHitsTtl 로 함께 연장한다.
     * Redis 응답이 null 이면 0 을 반환한다.
     */
    public long incrementHits(String hash) {
        String key = hitsKey(hash);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, cacheTtl);
        }
        return count == null ? 0L : count;
    }

    /**
     * hash 의 히트 카운터 TTL 을 cacheTtl 로 연장한다. 캐시 본체가 배경 갱신으로
     * 다시 저장되는 시점에 함께 호출해, 계속 사용되는 캐시의 히트 카운터가 본체보다
     * 먼저 만료되지 않도록 한다. 카운터가 없으면(아직 히트된 적 없음) 아무 효과 없다.
     */
    public void renewHitsTtl(String hash) {
        redis.expire(hitsKey(hash), cacheTtl);
    }

    private String cacheKey(String hash) {
        return CACHE_PREFIX + hash;
    }

    private String linkKey(String jobId) {
        return LINK_PREFIX + jobId;
    }

    private String hitsKey(String hash) {
        return HITS_PREFIX + hash;
    }

    private String inflightKey(String hash) {
        return INFLIGHT_PREFIX + hash;
    }

    private String waitKey(String jobId) {
        return WAIT_PREFIX + jobId;
    }
}

package map.service.user.recommend;

import java.time.Duration;
import java.util.Optional;
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

    private final StringRedisTemplate redis;
    private final Duration cacheTtl;
    private final Duration linkTtl;

    public ReuseCacheStore(
            @Qualifier("cacheRedisTemplate") StringRedisTemplate redis,
            @Value("${redis.cache-ttl-seconds:604800}") long cacheTtlSeconds,
            @Value("${redis.cache-link-ttl-seconds:3600}") long linkTtlSeconds
    ) {
        this.redis = redis;
        this.cacheTtl = Duration.ofSeconds(cacheTtlSeconds);
        this.linkTtl = Duration.ofSeconds(linkTtlSeconds);
    }

    /**
     * hash 에 해당하는 캐시 본체(draft JSON) 조회. 없으면 Optional.empty.
     */
    public Optional<String> find(String hash) {
        return Optional.ofNullable(redis.opsForValue().get(cacheKey(hash)));
    }

    /**
     * hash 키에 draft JSON 을 cacheTtl 과 함께 저장(SET + EX). 기존 값 있으면 덮어쓴다
     * (신규 생성 및 백그라운드 통짜 갱신 양쪽에서 사용).
     */
    public void save(String hash, String payloadJson) {
        redis.opsForValue().set(cacheKey(hash), payloadJson, cacheTtl);
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
}

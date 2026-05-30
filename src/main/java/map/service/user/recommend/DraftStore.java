package map.service.user.recommend;

import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * DraftStore — 추천 draft JSON 의 Redis 기반 저장소
 *
 * jobId 별 추천 결과 JSON 문자열을 Redis 에 보관한다.
 * 키 형식은 "recommend:result:{jobId}" 이며, 모든 키에 동일 TTL 이 적용된다.
 * RecommendService.applyEdit / findDraft / research,
 * ScheduleService.persist, RecommendJobsConsumer.onMessage 에서 사용된다.
 *
 * KEY_PREFIX: "recommend:result:" 상수. 키 네임스페이스 분리.
 * redis: @Qualifier("draftsRedisTemplate") StringRedisTemplate.
 *        draft 전용 Redis 연결을 사용하는 템플릿.
 * ttl: redis.draft-ttl-seconds 프로퍼티(기본 3600) 기반 Duration. 모든 set/touch 에 적용.
 */
@Component
public class DraftStore {

    private static final String KEY_PREFIX = "recommend:result:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    /**
     * 의존성 주입 생성자.
     *
     * redis: draft 전용 StringRedisTemplate.
     * ttlSeconds: redis.draft-ttl-seconds 프로퍼티 값. 미지정 시 3600초.
     */
    public DraftStore(
            @Qualifier("draftsRedisTemplate") StringRedisTemplate redis,
            @Value("${redis.draft-ttl-seconds:3600}") long ttlSeconds
    ) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /**
     * jobId 키에 payloadJson 을 ttl 과 함께 저장 (SET + EX).
     *
     * 기존 값이 있으면 덮어쓴다.
     *
     * jobId: 작업 식별자.
     * payloadJson: draft JSON 문자열.
     */
    public void save(String jobId, String payloadJson) {
        redis.opsForValue().set(key(jobId), payloadJson, ttl);
    }

    /**
     * jobId 키 값을 조회 (GET).
     *
     * 없으면 Optional.empty 반환.
     *
     * jobId: 작업 식별자.
     */
    public Optional<String> find(String jobId) {
        return Optional.ofNullable(redis.opsForValue().get(key(jobId)));
    }

    /**
     * jobId 키의 TTL 을 ttl 로 갱신 (EXPIRE).
     *
     * 키가 존재하지 않으면 false. Redis 응답이 null 이면 false.
     *
     * jobId: 작업 식별자.
     */
    public boolean touch(String jobId) {
        Boolean result = redis.expire(key(jobId), ttl);
        return Boolean.TRUE.equals(result);
    }

    /**
     * jobId 키 삭제 (DEL).
     *
     * 키가 존재하지 않으면 false. Redis 응답이 null 이면 false.
     *
     * jobId: 작업 식별자.
     */
    public boolean delete(String jobId) {
        Boolean result = redis.delete(key(jobId));
        return Boolean.TRUE.equals(result);
    }

    /**
     * jobId 를 KEY_PREFIX 와 결합하여 Redis 키 문자열을 생성.
     */
    private String key(String jobId) {
        return KEY_PREFIX + jobId;
    }
}

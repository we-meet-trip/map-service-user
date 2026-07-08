package map.service.user.recommend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import map.service.user.recommend.dto.EditRequest;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.RecommendRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * RecommendService — 추천 도메인 비즈니스 로직
 *
 * RecommendController 가 위임하는 추천 작업의 생성/조회/수정/재요청 흐름을 담당한다.
 * 외부 agent 호출은 AgentClient 로, draft 상태 보관/조회는 DraftStore 로,
 * draft JSON 의 부분 머지는 ObjectMapper 로 처리한다.
 *
 * 재사용 캐시(reuse cache):
 * createRecommendation 은 RecommendCacheKey 로 정규화한 해시를 ReuseCacheStore 에서
 * 조회해 히트/미스로 분기한다. 미스는 기존과 동일하게 agent 를 호출하고 job_id 를
 * hash 에 연결해 둔다(완료 시 RecommendJobsConsumer 가 캐시를 채운다). 히트는
 * 캐시된 draft 를 새 job_id 로 즉시 복사해 반환하고(agent 대기 없음, stale-while-
 * revalidate), 히트 카운터가 refreshEveryHits 의 배수가 될 때만 백그라운드로 agent 를
 * 재호출해 캐시를 통짜로 갱신한다. 캐시 관련 Redis 호출은 모두 실패를 삼키고 로그만
 * 남긴다 — 캐시는 최적화 계층이지 필수 경로가 아니다.
 *
 * agentClient: AgentClient. 추천 작업을 agent 서비스에 위임.
 * draftStore: DraftStore. draft JSON 상태 저장소(Redis 기반).
 * objectMapper: Jackson ObjectMapper. JSON 트리 파싱/직렬화에 사용.
 * reuseCacheStore: ReuseCacheStore. 재사용 캐시 본체/연결고리/히트카운터 저장소.
 * cacheKeyBuilder: RecommendCacheKey. RecommendRequest → 정규화 해시.
 * cacheRefreshExecutor: 백그라운드 캐시 갱신용 Executor(recommendCacheRefreshExecutor).
 * refreshEveryHits: recommend.cache-refresh-every-hits 프로퍼티(기본 3).
 */
@Service
public class RecommendService {

    private static final Logger log = LoggerFactory.getLogger(RecommendService.class);

    private final AgentClient agentClient;
    private final DraftStore draftStore;
    private final ObjectMapper objectMapper;
    private final ReuseCacheStore reuseCacheStore;
    private final RecommendCacheKey cacheKeyBuilder;
    private final Executor cacheRefreshExecutor;
    private final long refreshEveryHits;

    public RecommendService(
            AgentClient agentClient,
            DraftStore draftStore,
            ObjectMapper objectMapper,
            ReuseCacheStore reuseCacheStore,
            RecommendCacheKey cacheKeyBuilder,
            @Qualifier("recommendCacheRefreshExecutor") Executor cacheRefreshExecutor,
            @Value("${recommend.cache-refresh-every-hits:3}") long refreshEveryHits
    ) {
        this.agentClient = agentClient;
        this.draftStore = draftStore;
        this.objectMapper = objectMapper;
        this.reuseCacheStore = reuseCacheStore;
        this.cacheKeyBuilder = cacheKeyBuilder;
        this.cacheRefreshExecutor = cacheRefreshExecutor;
        this.refreshEveryHits = refreshEveryHits;
    }

    /**
     * 신규 추천 작업 생성 — 재사용 캐시 히트/미스에 따라 분기한다.
     *
     * 미스: AgentClient.requestRecommend 결과를 그대로 반환하고, job_id 를 hash 에
     * 연결해 완료 시 캐시가 채워지도록 한다.
     * 히트: 캐시된 draft 를 새 job_id 로 복사해 즉시 반환한다(agent 대기 없음). 히트
     * 카운터가 refreshEveryHits 의 배수이면 백그라운드로 캐시를 갱신한다(응답과 무관).
     *
     * request: 클라이언트에서 검증 완료된 RecommendRequest.
     */
    public JobAccepted createRecommendation(RecommendRequest request) {
        String hash = cacheKeyBuilder.hash(request);
        Optional<String> cached = findCached(hash);

        if (cached.isEmpty()) {
            JobAccepted accepted = agentClient.requestRecommend(request);
            linkJobSafely(accepted.jobId(), hash);
            return accepted;
        }

        String jobId = UUID.randomUUID().toString();
        draftStore.save(jobId, cached.get());
        maybeTriggerBackgroundRefresh(request, hash);
        return new JobAccepted(jobId, "in_progress", 3);
    }

    /**
     * ReuseCacheStore.find 를 호출하되, 조회 실패는 캐시 미스로 취급한다(무중단 원칙).
     */
    private Optional<String> findCached(String hash) {
        try {
            return reuseCacheStore.find(hash);
        } catch (RuntimeException e) {
            log.warn("reuse cache lookup failed hash={} reason={}", hash, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * job_id 를 hash 에 연결한다. 실패해도 추천 자체는 이미 성공했으므로 로그만 남긴다.
     */
    private void linkJobSafely(String jobId, String hash) {
        try {
            reuseCacheStore.linkJob(jobId, hash);
        } catch (RuntimeException e) {
            log.warn("reuse cache link failed job_id={} hash={} reason={}",
                    jobId, hash, e.getMessage());
        }
    }

    /**
     * 히트 카운터를 증가시키고, refreshEveryHits 의 배수이면 백그라운드 갱신을 예약한다.
     * 카운터 증가 실패는 갱신을 건너뛸 뿐 응답(이미 만든 히트 응답)에는 영향을 주지 않는다.
     */
    private void maybeTriggerBackgroundRefresh(RecommendRequest request, String hash) {
        long hits;
        try {
            hits = reuseCacheStore.incrementHits(hash);
        } catch (RuntimeException e) {
            log.warn("reuse cache hit-count failed hash={} reason={}", hash, e.getMessage());
            return;
        }
        if (hits % refreshEveryHits == 0) {
            cacheRefreshExecutor.execute(() -> refreshCache(request, hash));
        }
    }

    /**
     * 백그라운드 스레드에서 agent 를 재호출해 캐시를 통짜로 갱신 준비를 한다
     * (fire-and-forget). 완료 이벤트가 오면 RecommendJobsConsumer 가
     * ReuseCacheStore.save 로 실제 캐시 본체를 덮어쓴다. 실패해도 클라이언트
     * 응답(이미 나간 202)에는 영향 없음 — 로그만 남기고 다음 refreshEveryHits
     * 배수 히트에서 재시도된다.
     */
    private void refreshCache(RecommendRequest request, String hash) {
        try {
            JobAccepted accepted = agentClient.requestRecommend(request);
            reuseCacheStore.linkJob(accepted.jobId(), hash);
        } catch (RuntimeException e) {
            log.warn("reuse cache background refresh failed hash={} reason={}",
                    hash, e.getMessage());
        }
    }

    /**
     * jobId 에 해당하는 draft JSON 조회.
     *
     * DraftStore.find 위임. 존재 여부에 따라 Optional 로 반환한다.
     *
     * jobId: 조회 대상 작업 식별자.
     */
    public Optional<String> findDraft(String jobId) {
        return draftStore.find(jobId);
    }

    /**
     * 기존 draft JSON 에 EditRequest 의 비-null 필드를 shallow merge.
     *
     * draft 가 없거나 JSON 루트가 ObjectNode 가 아니면 Optional.empty 반환.
     * EditRequest 의 places / visitOrder / legs 각각에 대해 non-null 인 경우에만
     * 해당 키를 덮어쓴다. 머지 결과는 DraftStore.save 로 다시 저장한 뒤 반환한다.
     * JSON 파싱 실패 시 IllegalStateException 으로 전환한다.
     *
     * jobId: 대상 작업 식별자.
     * edit: EditRequest. places / visit_order / legs 부분 수정 데이터.
     */
    public Optional<String> applyEdit(String jobId, EditRequest edit) {
        Optional<String> current = draftStore.find(jobId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(current.get());
            if (!(root instanceof ObjectNode obj)) {
                return Optional.empty();
            }
            JsonNode editNode = objectMapper.valueToTree(edit);
            if (edit.places() != null) {
                obj.set("places", editNode.get("places"));
            }
            if (edit.visitOrder() != null) {
                obj.set("visit_order", editNode.get("visit_order"));
            }
            if (edit.legs() != null) {
                obj.set("legs", editNode.get("legs"));
            }
            String merged = objectMapper.writeValueAsString(obj);
            draftStore.save(jobId, merged);
            return Optional.of(merged);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("draft json parse failed", e);
        }
    }

    /**
     * 재추천 요청 처리.
     *
     * 캐시 조회/저장을 전혀 타지 않는다 — 사용자가 명시적으로 다른 결과를 원해서
     * 호출하는 액션이므로 항상 agent 를 재호출한다. 기존 jobId 의 draft 를
     * DraftStore.delete 로 폐기한 뒤 AgentClient.requestRecommend 로 신규 추천
     * 작업을 생성한다.
     *
     * jobId: 폐기할 기존 작업 식별자.
     * request: 신규 추천에 사용할 RecommendRequest.
     */
    public JobAccepted research(String jobId, RecommendRequest request) {
        draftStore.delete(jobId);
        return agentClient.requestRecommend(request);
    }
}

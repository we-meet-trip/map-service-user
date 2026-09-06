package map.service.user.recommend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.recommend.dto.EditRequest;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.RecommendRequest;
import map.service.user.recommend.dto.SelectedPlace;
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

    /** agent B2 계약의 exclude 목록 상한(초과분은 앞에서부터 절단). */
    private static final int EXCLUDE_MAX = 50;

    /**
     * 고른 테마 + 저장된 취향을 합쳤을 때의 상한.
     *
     * 받는 쪽이 테마 하나당 장소 검색을 한 번씩 던지므로 개수가 곧 외부
     * 호출량이고 응답 지연이다. 직접 고른 테마는 이 상한 때문에 잘리지
     * 않는다 — 이미 상한을 채웠으면 취향을 아예 덧붙이지 않는다.
     */
    private static final int THEME_MERGE_MAX = 6;

    private final AgentClient agentClient;
    @Value("${training.capture.enabled:false}")
    private boolean trainingCaptureEnabled;
    private final DraftStore draftStore;
    private final ObjectMapper objectMapper;
    private final ResearchLimitService researchLimitService;
    private final RecommendJobStore jobStore;
    private final ReuseCacheStore reuseCacheStore;
    private final RecommendCacheKey cacheKeyBuilder;
    private final ProfileThemeProvider profileThemeProvider;
    private final Executor cacheRefreshExecutor;
    private final long refreshEveryHits;
    private final map.service.user.schedule.ScheduleRepository schedules;

    public RecommendService(
            AgentClient agentClient,
            DraftStore draftStore,
            ObjectMapper objectMapper,
            ResearchLimitService researchLimitService,
            RecommendJobStore jobStore,
            ReuseCacheStore reuseCacheStore,
            RecommendCacheKey cacheKeyBuilder,
            ProfileThemeProvider profileThemeProvider,
            @Qualifier("recommendCacheRefreshExecutor") Executor cacheRefreshExecutor,
            @Value("${recommend.cache-refresh-every-hits:3}") long refreshEveryHits,
            map.service.user.schedule.ScheduleRepository schedules
    ) {
        this.schedules = schedules;
        this.agentClient = agentClient;
        this.draftStore = draftStore;
        this.objectMapper = objectMapper;
        this.researchLimitService = researchLimitService;
        this.jobStore = jobStore;
        this.reuseCacheStore = reuseCacheStore;
        this.cacheKeyBuilder = cacheKeyBuilder;
        this.profileThemeProvider = profileThemeProvider;
        this.cacheRefreshExecutor = cacheRefreshExecutor;
        this.refreshEveryHits = refreshEveryHits;
    }

    /**
     * 신규 추천 작업 생성 — 재사용 캐시 히트/미스에 따라 분기한다.
     *
     * 클라이언트가 보낸 stage/exclude 는 신뢰하지 않고 서버측에서
     * stage="init", exclude=[] 로 강제 재구성한다(남용 방지 — exclude 는
     * Mode 1 재탐색 전용이며 research 가 draft 에서 구성한다). 캐시 해시도
     * 정규화된 요청으로 계산한다.
     *
     * 미스: AgentClient.requestRecommend 결과를 그대로 반환하고, job_id 를 hash 에
     * 연결해 완료 시 캐시가 채워지도록 한다. 접수 직후 PG 에 in_progress 로
     * write-through 기록한다(best-effort, 실패해도 무시). 완료 이벤트는
     * RecommendJobsConsumer 가 수신한다.
     * 히트: 캐시된 draft 를 새 job_id 로 복사해 즉시 반환한다(agent 대기 없음). 히트
     * 카운터가 refreshEveryHits 의 배수이면 백그라운드로 캐시를 갱신한다(응답과 무관).
     *
     * request: 클라이언트에서 검증 완료된 RecommendRequest.
     */
    public JobAccepted createRecommendation(RecommendRequest request) {
        return createRecommendationDetailed(request).accepted();
    }

    /**
     * createRecommendation 과 동일한 로직을 수행하되, 캐시 히트 여부를 함께 반환한다.
     * RecommendController 가 X-Recommend-Cache 디버그 헤더를 채우기 위해 사용한다
     * (검증/관측 목적 — 응답 본문 계약에는 영향 없음).
     */
    public RecommendationResult createRecommendationDetailed(RecommendRequest request) {
        return createRecommendationDetailed(request, null);
    }

    /**
     * 위와 같되, 로그인한 사용자면 저장해 둔 취향을 테마에 얹는다.
     *
     * <p>취향은 <b>캐시를 조회한 뒤에만</b> 붙인다. 캐시 키를 취향이 섞인
     * 값으로 계산하면 이미 쌓아 둔 캐시가 통째로 빗나가고, 취향이 다른
     * 사용자마다 키가 갈라져 적중률이 무너진다.
     *
     * <p>취향이 붙은 요청의 결과는 <b>캐시에 저장하지 않는다.</b> 그 결과는
     * 그 사람에게 맞춘 것이라 같은 조건으로 요청한 다른 사람에게 내보내면
     * 안 된다. 저장을 막는 방법은 완료된 job 을 캐시 키에 연결하지 않는
     * 것이다 — 연결이 없으면 완료 이벤트를 받아도 캐시 본체를 쓰지 않는다.
     *
     * <p>대신 캐시가 맞은 요청은 취향을 반영하지 못한다. 같은 사용자라도
     * 캐시 상태에 따라 반영 여부가 갈리는데, 이는 기존 캐시를 무효화하지
     * 않기 위해 받아들인 맞바꿈이다.
     *
     * userId: 인증된 사용자 식별자. null 이면 취향을 조회하지 않고
     *         기존 경로와 완전히 같게 동작한다.
     */
    public RecommendationResult createRecommendationDetailed(
            RecommendRequest request, Long userId) {
        // 클라이언트가 보낸 stage/exclude 는 신뢰하지 않는다. 캐시 조회·agent 호출·
        // 백그라운드 갱신 모두 정규화된 요청 하나만 사용해 경로 간 해시가 어긋나지 않게 한다.
        // final 로 둔다 — 아래 취향 병합이 이 값을 덮으면 캐시 키와 백그라운드
        // 갱신이 함께 오염된다.
        final RecommendRequest normalized = withStage(request, "init", List.of());
        final String hash = cacheKeyBuilder.hash(normalized);
        Optional<String> cached = findCached(hash);

        if (cached.isEmpty()) {
            List<String> merged = mergeProfileThemes(normalized.theme(), userId);
            // 취향이 섞이지 않은 요청만 하나로 묶는다. 해시는 취향을 섞기 전
            // 값으로 만들어지므로, 취향이 다른 두 사람이 같은 해시를 만든다.
            // 그 상태로 묶으면 뒤에 온 사람이 앞사람 취향이 반영된 결과를 받는다.
            if (merged == null && !reuseCacheStore.tryBecomeProducer(hash)) {
                return followProducer(normalized, userId);
            }
            RecommendRequest outbound =
                    merged == null ? normalized : withTheme(normalized, merged);
            JobAccepted accepted = agentClient.requestRecommend(outbound);
            if (merged == null) {
                linkJobSafely(accepted.jobId(), hash);
            } else {
                log.info(
                        "reuse cache link skipped (themes merged) hash={} themes={}",
                        hash, merged.size());
            }
            jobStore.insertInProgress(accepted.jobId(), normalized.scheduleId(),
                    RecommendJobStore.JobOrigin.agent("init").ownedBy(userId)
                            .withSegment(segmentOf(userId, merged != null)),
                    normalized.province(), normalized.city());
            return new RecommendationResult(accepted, false);
        }

        String jobId = UUID.randomUUID().toString();
        // 캐시 본체는 최초로 그 결과를 만든 job 의 job_id 를 담고 있다. 그대로
        // 복사하면 GET /api/v1/recommend/{jobId} 가 경로와 다른 job_id 를 담은
        // 본문을 돌려주어(응답·Redis draft·PG result_payload 전부) 호출 측이
        // 본문 값으로 후속 요청을 만들면 남의 job 에 작용한다. 사본에만 이번
        // job_id 를 덮어쓰고 캐시 본체는 건드리지 않는다.
        // 덮어쓰기 전에 원본을 꺼내 둔다. 이 값이 사본과, 후보·선택 근거를
        // 들고 있는 원본 잡을 잇는 유일한 끈이다.
        String origin = originJobId(cached.get());
        String payload = withJobId(cached.get(), jobId);
        // 캐시 히트는 agent job 이 없어 완료 이벤트가 영영 오지 않는다. 즉시 완료로
        // 기록해야 findDraft 의 PG 폴백(Redis draft TTL 만료 이후)과 admin 콘솔
        // 추천작업 목록·통계에서 누락되지 않는다. insertInProgress 가 먼저
        // schedule_id 를 심고 markFinished 는 기존 행을 갱신하므로 값이 보존된다.
        jobStore.insertInProgress(jobId, normalized.scheduleId(),
                RecommendJobStore.JobOrigin.cacheHit(origin).ownedBy(userId)
                        .withSegment(segmentOf(userId, false)),
                normalized.province(), normalized.city());
        jobStore.recordCompletion(jobId, "done", payload, null);
        cacheDraft(jobId, payload);
        maybeTriggerBackgroundRefresh(normalized, hash);
        return new RecommendationResult(new JobAccepted(jobId, "in_progress", 3), true);
    }

    /**
     * 캐시 사본의 job_id 를 이번 job 식별자로 교체한다. 캐시 본체는 변경하지 않는다.
     *
     * 파싱 실패나 루트가 객체가 아닌 경우에는 원문을 그대로 돌려준다. 캐시 히트는
     * 순수 최적화 경로이므로 여기서 예외를 던져 요청을 실패시키지 않는다(무중단 원칙).
     */
    /**
     * 물어본 시점의 요청자 성향을 만든다. 모르면 null.
     *
     * 잡에 붙여 두는 이유는 나중에 조인으로 읽으면 사람이 프로필을 고친 뒤
     * 과거 잡의 입력까지 함께 바뀌기 때문이다. 학습은 같은 기록을 여러 번
     * 읽으므로 그때마다 값이 달라지면 안 된다.
     *
     * themeMerged 는 그 요청의 테마에 저장된 취향이 섞였는지다. 섞인 요청은
     * 입력에 이미 성향이 들어가 있어, 성향을 따로 쓰는 학습에서 같은 것을
     * 두 번 세게 된다 — 그것을 갈라 보려면 표시가 있어야 한다.
     */
    private Map<String, Object> segmentOf(Long userId, boolean themeMerged) {
        return trainingCaptureEnabled ? profileThemeProvider.segmentFor(userId, themeMerged) : null;
    }

    /**
     * 캐시 본체가 담고 있는 "이 결과를 처음 만든 잡" 의 식별자를 꺼낸다.
     *
     * 이 값이 사본과 원본을 잇는 유일한 끈이다. 사본은 곧바로 job_id 를 자기
     * 것으로 덮어쓰므로, 덮기 전에 꺼내 두지 않으면 원본을 되짚을 길이 없다.
     * 원본에는 후보와 선택 근거(학습 신호)가 붙어 있는데, 끈이 없으면 사용자가
     * 실제로 저장한 일정이 어떤 후보에서 나왔는지 영영 알 수 없게 된다.
     *
     * 못 꺼내면 null 이다. 캐시 히트는 순수 최적화 경로라 여기서 실패해도
     * 요청을 깨뜨리지 않는다 — 계보만 비고 나머지는 그대로 동작한다.
     */
    private String originJobId(String payloadJson) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            if (root instanceof ObjectNode obj) {
                JsonNode id = obj.get("job_id");
                if (id != null && id.isTextual()) {
                    return id.asText();
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("reuse cache payload origin job_id read skipped: {}", e.getMessage());
        }
        return null;
    }

    private String withJobId(String payloadJson, String jobId) {
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            if (!(root instanceof ObjectNode obj)) {
                return payloadJson;
            }
            obj.put("job_id", jobId);
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("reuse cache payload job_id rewrite skipped: {}", e.getMessage());
            return payloadJson;
        }
    }

    /**
     * createRecommendationDetailed 의 결과 캐리어.
     *
     * accepted: 기존과 동일한 JobAccepted 응답 본문.
     * cacheHit: 재사용 캐시 히트 여부(디버그 헤더용, 응답 본문에는 포함되지 않음).
     */
    public record RecommendationResult(JobAccepted accepted, boolean cacheHit) {
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
            // 이 경로만 접수 기록을 남기지 않아, 뒤에 오는 완료 이벤트가 출처
            // 없는 행을 만들었다. 그러면 통계에서 사용자 요청과 구분되지 않는다.
            jobStore.insertInProgress(accepted.jobId(), request.scheduleId(),
                    RecommendJobStore.JobOrigin.agent("refresh"));
        } catch (RuntimeException e) {
            log.warn("reuse cache background refresh failed hash={} reason={}",
                    hash, e.getMessage());
        }
    }

    /**
     * jobId 에 해당하는 draft JSON 조회(Redis 우선, PG 폴백).
     *
     * 1) DraftStore.find(Redis) 히트 시 그대로 반환.
     * 2) 미스 시 RecommendJobStore.findFinishedPayload(PG)로 완료 결과를 폴백 조회한다.
     *    폴백 히트 시 draftStore.save 로 Redis 를 재적재(re-warm)한 뒤 반환한다.
     * 3) 둘 다 없으면 Optional.empty(long-poll 응답 형식은 컨트롤러가 그대로 유지).
     *
     * jobId: 조회 대상 작업 식별자. 유효 UUID 가 아니면 PG 폴백은 자연히 empty.
     */
    public Optional<String> findOwnedDraft(String jobId, Long userId) {
        jobStore.requireOwned(jobId, userId);
        return findDraft(jobId);
    }

    public Optional<String> findDraft(String jobId) {
        // PG is authoritative after edits. A stale/failed Redis write must never undo an edit.
        Optional<String> durable = jobStore.findFinishedPayload(jobId);
        if (durable.isPresent()) return durable;
        try {
            Optional<String> hit = draftStore.find(jobId);
            return hit.isPresent() ? hit : resolveWaiting(jobId);
        } catch (RuntimeException e) {
            log.warn("recommend draft cache unavailable job_id={} cause={}", jobId, e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private void cacheDraft(String jobId, String payload) {
        try {
            draftStore.save(jobId, payload);
        } catch (RuntimeException e) {
            log.warn("recommend draft cache write unavailable job_id={} cause={}", jobId, e.getClass().getSimpleName());
        }
    }

    /**
     * 같은 조건을 먼저 요청한 쪽에 붙는다. agent 를 부르지 않는다.
     *
     * 앞선 요청이 만들어 캐시에 넣으면 이 job 도 그것으로 답한다. 언제
     * 채워지는지는 조회 시점에 확인하므로(resolveWaiting) 여기서 기다리지
     * 않는다 — 기다리면 접수 응답이 그만큼 늦어진다.
     */
    private RecommendationResult followProducer(RecommendRequest normalized, Long userId) {
        String jobId = UUID.randomUUID().toString();
        reuseCacheStore.markWaiting(jobId, cacheKeyBuilder.hash(normalized));
        jobStore.insertInProgress(jobId, normalized.scheduleId(),
                RecommendJobStore.JobOrigin.agent("init").ownedBy(userId)
                        .withSegment(segmentOf(userId, false)));
        log.info("joined in-flight recommendation job_id={}", jobId);
        return new RecommendationResult(
                new JobAccepted(jobId, "in_progress", 3), false);
    }

    /**
     * 기다리던 결과가 나왔는지 보고, 나왔으면 이 job 의 것으로 만들어 준다.
     *
     * 캐시 본문을 그대로 주지 않고 job_id 를 이 job 것으로 바꿔 복사한다.
     * 같은 본문을 여러 사람이 함께 쓰면, 한 사람이 고친 것이 다른 사람 결과를
     * 바꾼다. 캐시 적중 경로가 이미 같은 방식으로 복사하고 있다.
     *
     * 아직 안 나왔으면 empty 를 돌려준다 — 조회하는 쪽은 진행 중으로 보고
     * 다시 물어본다. 앞선 요청이 끝내 실패하면 캐시가 채워지지 않아 기다림
     * 표시의 시한이 끝나고, 조회 쪽 시한도 함께 끝난다.
     */
    private Optional<String> resolveWaiting(String jobId) {
        Optional<String> hash = reuseCacheStore.waitingHash(jobId);
        if (hash.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> produced = reuseCacheStore.find(hash.get());
        if (produced.isEmpty()) {
            return Optional.empty();
        }
        // 접수할 때는 캐시로 답하게 될지 몰라 agent 로 적어 두었다. 이제 어느
        // 잡이 만든 결과인지 알게 됐으니 계보만 채운다 — 이것이 없으면 이
        // 잡으로 저장된 일정은 후보를 되짚을 수 없다.
        String origin = originJobId(produced.get());
        String payload = withJobId(produced.get(), jobId);
        if (origin != null) {
            jobStore.insertInProgress(jobId, null,
                    RecommendJobStore.JobOrigin.joined(origin));
        }
        jobStore.recordCompletion(jobId, "done", payload, null);
        cacheDraft(jobId, payload);
        reuseCacheStore.clearWaiting(jobId);
        log.info("in-flight join resolved job_id={}", jobId);
        return Optional.of(payload);
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
        return applyEdit(jobId, edit, null, null);
    }

    /** Apply the patch under a PG row lock and replay the original accepted response on retry. */
    public Optional<String> applyEdit(String jobId, EditRequest edit,
                                      Long userId, String idempotencyKey) {
        jobStore.requireOwned(jobId, userId);
        try {
            String requestJson = objectMapper.writeValueAsString(edit);
            RecommendJobStore.EditOutcome outcome = jobStore.persistOwnedEdit(
                    jobId, userId, idempotencyKey, requestJson, current -> mergeEdit(current, edit));
            if (outcome == null) return Optional.empty();
            cacheDraft(jobId, outcome.canonical());
            return Optional.of(outcome.response());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("edit json serialization failed", e);
        }
    }

    private String mergeEdit(String current, EditRequest edit) {
        try {
            JsonNode root = objectMapper.readTree(current);
            if (!(root instanceof ObjectNode obj)) return null;
            JsonNode editNode = objectMapper.valueToTree(edit);
            if (edit.places() != null) obj.set("places", editNode.get("places"));
            if (edit.visitOrder() != null) obj.set("visit_order", editNode.get("visit_order"));
            if (edit.legs() != null) obj.set("legs", editNode.get("legs"));
            requireConsistent(obj);
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("draft json parse failed", e);
        }
    }

    /**
     * 고친 초안이 스스로 앞뒤가 맞는지 본다. 안 맞으면 저장하지 않는다.
     *
     * visit_order 와 legs 는 장소를 이름이 아니라 place_id 로 가리킨다. 장소를
     * 빼면서 순서를 함께 고쳐 보내지 않으면, 남은 순서가 이제 없는 장소를
     * 가리킨다.
     *
     * 그렇게 저장하면 요청은 성공으로 돌아오는데 그 일정을 다시 열 때 방문지가
     * 하나도 나오지 않는다. 그리는 쪽이 순서에 적힌 장소를 못 찾으면 부분만
     * 그리지 않고 통째로 포기하기 때문이다. 저장한 사람은 지운 것 하나만
     * 빠졌으리라 믿고 있으므로, 빈 일정을 보기 전까지 알 수 없다.
     *
     * 서버가 순서를 대신 고쳐 주지는 않는다. 남은 것을 임의로 이어 붙이면
     * 사용자가 원하지 않은 동선이 된다. 맞지 않으면 거절하고 부르는 쪽이
     * 셋을 함께 보내게 한다.
     *
     * 판정 기준은 그리는 쪽(TripStopsAssembler.orderPlaces)과 같아야 한다.
     * 다르면 여기서 통과한 것이 거기서 터지거나 그 반대가 된다.
     */
    private void requireConsistent(ObjectNode draft) {
        Set<Integer> known = new HashSet<>();
        for (JsonNode place : draft.path("places")) {
            JsonNode id = place.path("place_id");
            if (id.isIntegralNumber()) {
                known.add(id.asInt());
            }
        }
        for (JsonNode id : draft.path("visit_order")) {
            requireKnown(id, known);
        }
        for (JsonNode leg : draft.path("legs")) {
            requireKnown(leg.path("from"), known);
            requireKnown(leg.path("to"), known);
        }
    }

    private void requireKnown(JsonNode node, Set<Integer> known) {
        if (!node.isIntegralNumber() || !known.contains(node.asInt())) {
            throw new CustomException(ErrorCode.RECOMMEND_EDIT_INCONSISTENT);
        }
    }

    /**
     * 재추천(Mode 1) 요청 처리.
     *
     * 재사용 캐시 조회/저장을 전혀 타지 않는다 — 사용자가 명시적으로 다른 결과를
     * 원해서 호출하는 액션이므로 항상 agent 를 재호출한다.
     *
     * 기존 draft 의 places[].content_id 를 수집해 exclude 목록을 만들고
     * (실측 근거 없는 항목은 content_id 가 없어 자연 제외), draft 를
     * DraftStore.delete 로 폐기한 뒤 stage="mode1" + exclude 로 재구성한 요청을
     * agent 에 위임한다(재탐색 제외 목록). draft 가 없거나 파싱 불가하면
     * exclude 없이 진행한다(기존 동작 보존).
     *
     * 재추천 일일 한도(ResearchLimitService)를 가장 먼저 검사한다. scheduleId 가
     * 있으면 일정 단위("sched:{id}"), 없으면 잡 단위("job:{jobId}") 버킷으로
     * 카운트한다. 한도 초과 시 CustomException(RESEARCH_LIMIT_EXCEEDED, 409)을
     * 던지며, 이때 draft 삭제·agent 호출은 수행하지 않는다.
     *
     * jobId: 폐기할 기존 작업 식별자.
     * request: 신규 추천에 사용할 RecommendRequest.
     */
    public JobAccepted research(String jobId, RecommendRequest request) {
        return research(jobId, request, List.of(), null, null);
    }

    /**
     * 위와 같되, 누가 다시 짜기를 눌렀는지 함께 남긴다.
     *
     * 다시 짜기는 "앞의 결과를 받지 않겠다" 는 뜻이라 사람의 판단이 가장
     * 뚜렷하게 드러나는 자리다. 누가 그랬는지가 없으면 그 판단을 어떤 성향과
     * 짝지을 수 없어 신호로 쓰이지 못한다.
     */
    public JobAccepted research(String jobId, RecommendRequest request, Long userId) {
        return research(jobId, request, List.of(), null, userId);
    }

    /**
     * 위와 같되, 호출 측이 제외 목록과 남길 장소를 함께 지정한다.
     *
     * extraExclude 를 따로 받는 이유는 이전 draft 가 이미 사라졌을 수 있기
     * 때문이다 — 만료되거나 일정으로 저장되면 지워진다. 화면은 방금 본
     * 장소들을 들고 있으므로 그 목록이 서버가 되찾는 것보다 오래 남는다.
     * 둘을 합집합으로 쓰되 순서를 보존하고 계약 상한까지만 싣는다.
     *
     * keep 은 "이 장소는 그대로 두라"는 뜻이다. agent 가 그 자리를 고정하고
     * 나머지만 다시 뽑는다. keep 에 든 장소도 exclude 에 함께 들어 있어야
     * 새로 뽑는 쪽에서 같은 곳이 다시 나오지 않는다 — 호출 측이 이전 추천
     * 전체를 exclude 로 보내므로 자연히 그렇게 된다.
     *
     * jobId: 폐기할 기존 작업 식별자.
     * request: 신규 추천에 사용할 RecommendRequest.
     * extraExclude: 호출 측이 지정한 제외 목록. null 이면 빈 목록으로 본다.
     * keep: 고정할 장소 목록. null/빈 목록이면 전부 다시 뽑는다.
     */
    public JobAccepted research(
            String jobId, RecommendRequest request,
            List<String> extraExclude, List<SelectedPlace> keep) {
        return research(jobId, request, extraExclude, keep, null);
    }

    /** 제외·고정 목록과 누른 사람을 모두 받는 갈래. 위 갈래들이 여기로 모인다. */
    public JobAccepted research(
            String jobId, RecommendRequest request,
            List<String> extraExclude, List<SelectedPlace> keep, Long userId) {
        jobStore.requireOwned(jobId, userId);
        if (userId != null && request.scheduleId() != null && !request.scheduleId().isBlank()) {
            Long schedule;
            try { schedule = Long.valueOf(request.scheduleId()); }
            catch (NumberFormatException invalid) { throw new CustomException(ErrorCode.RECOMMEND_NOT_OWNER); }
            if (schedules.findByScheduleIdAndUserId(schedule, userId).isEmpty())
                throw new CustomException(ErrorCode.RECOMMEND_NOT_OWNER);
        }
        String limitKey = (request.scheduleId() != null && !request.scheduleId().isBlank())
                ? "sched:" + request.scheduleId()
                : "job:" + jobId;
        if (!researchLimitService.tryConsume(limitKey)) {
            throw new CustomException(ErrorCode.RESEARCH_LIMIT_EXCEEDED);
        }
        Set<String> exclude = new LinkedHashSet<>(collectExcludeContentIds(jobId));
        if (extraExclude != null) {
            for (String id : extraExclude) {
                if (id != null && !id.isBlank() && exclude.size() < EXCLUDE_MAX) {
                    exclude.add(id);
                }
            }
        }
        List<SelectedPlace> pinned =
                (keep == null || keep.isEmpty()) ? null : List.copyOf(keep);
        JobAccepted accepted = agentClient.requestRecommend(
                withStage(request, "mode1", List.copyOf(exclude), pinned));
        // 원본 jobId 를 함께 남긴다. 재탐색은 "앞의 결과를 버렸다" 는 뜻이라,
        // 무엇을 버리고 무엇을 받았는지가 쌍으로 있어야 신호가 된다.
        jobStore.insertInProgress(accepted.jobId(), request.scheduleId(),
                RecommendJobStore.JobOrigin.research(jobId).ownedBy(userId)
                        .withSegment(segmentOf(userId, false)),
                request.province(), request.city());
        return accepted;
    }

    /**
     * draft JSON 의 places[].content_id 를 순서 보존·중복 제거로 수집.
     *
     * agent B2 계약 상한(EXCLUDE_MAX=50)에 맞춰 절단한다. draft 부재,
     * JSON 파싱 실패, places 비배열 등은 전부 빈 목록으로 처리한다 —
     * exclude 는 best-effort 이며 재추천 자체를 막아선 안 된다.
     *
     * Redis 만 보면 안 된다. draft 는 만료되고 일정으로 저장될 때 지워지는데,
     * 그때 이 목록이 조용히 비면 "다른 장소"를 요구한 재탐색이 같은 장소를
     * 그대로 다시 내놓는다. findDraft 는 PG 에 남은 완료 결과까지 본다.
     *
     * jobId: 대상 작업 식별자.
     */
    private List<String> collectExcludeContentIds(String jobId) {
        Optional<String> draft = findDraft(jobId);
        if (draft.isEmpty()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(draft.get());
            JsonNode places = root.get("places");
            if (places == null || !places.isArray()) {
                return List.of();
            }
            Set<String> ids = new LinkedHashSet<>();
            for (JsonNode place : places) {
                JsonNode cid = place.get("content_id");
                if (cid != null && cid.isTextual()
                        && !cid.asText().isBlank()) {
                    ids.add(cid.asText());
                }
                if (ids.size() >= EXCLUDE_MAX) {
                    break;
                }
            }
            return List.copyOf(ids);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /**
     * stage/exclude 만 교체한 RecommendRequest 사본 생성(레코드 재구성).
     *
     * 나머지 필드(date/budget/theme/mobility/province/city/scheduleId)는
     * 원본을 그대로 유지한다.
     */
    private static RecommendRequest withStage(
            RecommendRequest request, String stage, List<String> exclude) {
        return withStage(request, stage, exclude, null);
    }

    /**
     * stage/exclude/places 를 교체한 RecommendRequest 사본 생성.
     *
     * places 를 함께 지정하는 이유는 탐색 기반 추천과 사용자 선택 동선이
     * 같은 요청 타입을 쓰기 때문이다. 초기 추천에서는 places 를 null 로
     * 지워 보내고, 동선 경로에서는 일정 전부를 채운다.
     *
     * 재탐색(mode1)에서는 "그대로 둘 장소"만 채운다. 받는 쪽이 stage 로
     * 둘을 구분하므로 exclude 와 함께 와도 모호하지 않다 — 고정할 곳은
     * places, 피할 곳은 exclude 다.
     */
    private static RecommendRequest withStage(
            RecommendRequest request, String stage, List<String> exclude,
            List<SelectedPlace> places) {
        return new RecommendRequest(
                request.date(),
                request.budget(),
                request.theme(),
                request.mobility(),
                request.province(),
                request.city(),
                request.scheduleId(),
                stage,
                exclude,
                places, "route".equals(stage) && request.optimize());
    }

    /**
     * theme 만 교체한 RecommendRequest 사본 생성.
     *
     * 다른 필드는 손대지 않는다. 캐시 키를 만든 원본과 이 사본의 차이가
     * theme 하나뿐이어야, 나중에 무엇 때문에 캐시에 넣지 않았는지가 분명하다.
     */
    private static RecommendRequest withTheme(
            RecommendRequest request, List<String> theme) {
        return new RecommendRequest(
                request.date(),
                request.budget(),
                theme,
                request.mobility(),
                request.province(),
                request.city(),
                request.scheduleId(),
                request.stage(),
                request.exclude(),
                request.places(), request.optimize());
    }

    /**
     * 사용자가 고른 테마 뒤에 저장해 둔 취향을 덧붙인다.
     *
     * 덧붙일 것이 하나도 없으면 <b>null</b> 을 돌려준다. 호출 측은 이 한
     * 가지 신호만 보고 "오늘과 똑같이 처리할지"를 정한다 — 빈 목록과
     * 원본을 구분하려 들면 판단 지점이 여러 곳으로 흩어진다.
     *
     * 고른 테마는 순서도 내용도 그대로 앞에 남긴다. 받는 쪽이 목록 앞을
     * 더 중요하게 다루므로, 직접 고른 것이 저장된 취향을 이긴다. 이미
     * 상한만큼 골랐으면 아무것도 덧붙이지 않는다 — 취향을 넣겠다고 사용자가
     * 직접 고른 테마를 밀어내면 안 된다.
     */
    private List<String> mergeProfileThemes(
            List<String> explicit, Long userId) {
        if (userId == null) {
            return null;
        }
        List<String> base = explicit == null ? List.of() : explicit;
        int room = THEME_MERGE_MAX - base.size();
        if (room <= 0) {
            return null;
        }
        List<String> profile;
        try {
            profile = profileThemeProvider.themesFor(userId);
        } catch (RuntimeException e) {
            // 취향은 추천에 얹는 덤이다. 조회처가 무엇이든 그 실패가 여행
            // 일정 자체를 실패시키면 안 된다 — 캐시 조회 실패를 미스로
            // 접는 것과 같은 규약이다.
            log.warn("profile themes unavailable, sending request as-is: {}",
                    e.getClass().getSimpleName());
            return null;
        }
        if (profile == null || profile.isEmpty()) {
            return null;
        }
        List<String> merged = new ArrayList<>(base);
        for (String code : profile) {
            if (merged.size() >= THEME_MERGE_MAX) {
                break;
            }
            if (!merged.contains(code)) {
                merged.add(code);
            }
        }
        return merged.size() == base.size() ? null : merged;
    }

    /**
     * 사용자가 고른 장소들의 동선 작업을 접수한다.
     *
     * 재사용 캐시를 타지 않는다. 캐시 키는 지역·기간·테마 같은 검색 조건으로
     * 만들어지는데, 이 요청의 본질은 "이 장소들"이라 같은 조건이라도 장소
     * 조합이 다르면 완전히 다른 결과가 나온다. 남의 조합을 돌려주는 사고를
     * 막기 위해 조회도 등록도 하지 않는다.
     *
     * stage 는 서버가 "route" 로 강제하고 exclude 는 비운다(재탐색 전용).
     * 접수 직후 PG 에 in_progress 로 기록해 진행 중 작업이 콘솔에서 보이게 한다.
     *
     * request: 검증 완료된 RecommendRequest. places 는 2~10개.
     */
    /**
     * 재사용 캐시를 건너뛰고 반드시 agent 를 돌리는 추천(날씨 변화 재추천용).
     *
     * 캐시를 조회하지 않는다. 캐시 키는 지역·기간·테마 같은 검색 조건으로만
     * 만들어지고 날씨는 들어가지 않는다. 그런데 날씨 재추천은 조건이 그대로인
     * 채 결과만 달라져야 하는 요청이라, 캐시를 태우면 반드시 히트해서 agent 가
     * 아예 돌지 않는다. agent 가 돌지 않으면 fetch_weather 도 돌지 않으니
     * 비 오기 전에 만든 그 야외 코스가 그대로 돌아온다 — 기능이 무력화된다.
     *
     * 결과를 캐시에 등록하지도 않는다. 비 오는 날 만든 실내 위주 코스가 맑은
     * 날 같은 조건 요청의 캐시로 재사용되면 반대 방향으로 같은 사고가 난다.
     *
     * 날씨 자체는 요청에 싣지 않는다. agent 의 fetch_weather 노드가 지역·기간으로
     * hub 에 직접 물으므로, 추천이 실제로 도는 시점의 예보가 반영된다. BFF 가
     * 본 값을 실어 보내면 그 사이 예보가 또 바뀌었을 때 오히려 어긋난다.
     *
     * stage 는 "init" 으로 강제하고 exclude 는 비운다 — 장소를 걸러내려는 게
     * 아니라 바뀐 날씨로 처음부터 다시 짜는 것이다.
     */
    public JobAccepted createFreshRecommendation(RecommendRequest request) {
        return createFreshRecommendation(request, null);
    }

    public JobAccepted createFreshRecommendation(RecommendRequest request, Long userId) {
        RecommendRequest normalized = withStage(request, "init", List.of());
        JobAccepted accepted = agentClient.requestRecommend(normalized);
        jobStore.insertInProgress(accepted.jobId(), normalized.scheduleId(),
                RecommendJobStore.JobOrigin.agent("init").ownedBy(userId),
                normalized.province(), normalized.city());
        return accepted;
    }

    public JobAccepted createRouteJob(RecommendRequest request) {
        return createRouteJob(request, null);
    }

    /**
     * 위와 같되, 고른 사람을 함께 남긴다.
     *
     * 이 경로는 사용자가 장소를 직접 골라 동선만 다시 만드는 것이라, 고른
     * 장소 자체가 사람이 남긴 선택이다. 누구의 선택인지가 없으면 성향과
     * 짝지을 수 없다.
     */
    public JobAccepted createRouteJob(RecommendRequest request, Long userId) {
        RecommendRequest normalized =
                withStage(request, "route", List.of(), request.places());
        JobAccepted accepted = agentClient.requestRecommend(normalized);
        jobStore.insertInProgress(accepted.jobId(), normalized.scheduleId(),
                RecommendJobStore.JobOrigin.agent("route").ownedBy(userId)
                        .withSegment(segmentOf(userId, false)),
                normalized.province(), normalized.city());
        return accepted;
    }
}

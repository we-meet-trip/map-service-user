package map.service.user.recommend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
    private final DraftStore draftStore;
    private final ObjectMapper objectMapper;
    private final ResearchLimitService researchLimitService;
    private final RecommendJobStore jobStore;
    private final ReuseCacheStore reuseCacheStore;
    private final RecommendCacheKey cacheKeyBuilder;
    private final ProfileThemeProvider profileThemeProvider;
    private final Executor cacheRefreshExecutor;
    private final long refreshEveryHits;

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
            @Value("${recommend.cache-refresh-every-hits:3}") long refreshEveryHits
    ) {
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
            jobStore.insertInProgress(accepted.jobId(), normalized.scheduleId());
            return new RecommendationResult(accepted, false);
        }

        String jobId = UUID.randomUUID().toString();
        // 캐시 본체는 최초로 그 결과를 만든 job 의 job_id 를 담고 있다. 그대로
        // 복사하면 GET /api/v1/recommend/{jobId} 가 경로와 다른 job_id 를 담은
        // 본문을 돌려주어(응답·Redis draft·PG result_payload 전부) 호출 측이
        // 본문 값으로 후속 요청을 만들면 남의 job 에 작용한다. 사본에만 이번
        // job_id 를 덮어쓰고 캐시 본체는 건드리지 않는다.
        String payload = withJobId(cached.get(), jobId);
        draftStore.save(jobId, payload);
        // 캐시 히트는 agent job 이 없어 완료 이벤트가 영영 오지 않는다. 즉시 완료로
        // 기록해야 findDraft 의 PG 폴백(Redis draft TTL 만료 이후)과 admin 콘솔
        // 추천작업 목록·통계에서 누락되지 않는다. insertInProgress 가 먼저
        // schedule_id 를 심고 markFinished 는 기존 행을 갱신하므로 값이 보존된다.
        jobStore.insertInProgress(jobId, normalized.scheduleId());
        jobStore.markFinished(jobId, "done", payload);
        maybeTriggerBackgroundRefresh(normalized, hash);
        return new RecommendationResult(new JobAccepted(jobId, "in_progress", 3), true);
    }

    /**
     * 캐시 사본의 job_id 를 이번 job 식별자로 교체한다. 캐시 본체는 변경하지 않는다.
     *
     * 파싱 실패나 루트가 객체가 아닌 경우에는 원문을 그대로 돌려준다. 캐시 히트는
     * 순수 최적화 경로이므로 여기서 예외를 던져 요청을 실패시키지 않는다(무중단 원칙).
     */
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
    public Optional<String> findDraft(String jobId) {
        Optional<String> hit = draftStore.find(jobId);
        if (hit.isPresent()) {
            return hit;
        }
        Optional<String> fallback = jobStore.findFinishedPayload(jobId);
        fallback.ifPresent(payload -> draftStore.save(jobId, payload));
        return fallback;
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
        String limitKey = (request.scheduleId() != null && !request.scheduleId().isBlank())
                ? "sched:" + request.scheduleId()
                : "job:" + jobId;
        if (!researchLimitService.tryConsume(limitKey)) {
            throw new CustomException(ErrorCode.RESEARCH_LIMIT_EXCEEDED);
        }
        List<String> exclude = collectExcludeContentIds(jobId);
        draftStore.delete(jobId);
        JobAccepted accepted = agentClient.requestRecommend(
                withStage(request, "mode1", exclude));
        jobStore.insertInProgress(accepted.jobId(), request.scheduleId());
        return accepted;
    }

    /**
     * draft JSON 의 places[].content_id 를 순서 보존·중복 제거로 수집.
     *
     * agent B2 계약 상한(EXCLUDE_MAX=50)에 맞춰 절단한다. draft 부재,
     * JSON 파싱 실패, places 비배열 등은 전부 빈 목록으로 처리한다 —
     * exclude 는 best-effort 이며 재추천 자체를 막아선 안 된다.
     *
     * jobId: 대상 작업 식별자.
     */
    private List<String> collectExcludeContentIds(String jobId) {
        Optional<String> draft = draftStore.find(jobId);
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
     * 같은 요청 타입을 쓰기 때문이다. 탐색 경로에서는 places 를 null 로
     * 지워 보내고, 동선 경로에서만 채운다 — 둘이 함께 오면 agent 가 어느
     * 쪽을 따를지 모호해진다.
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
                places);
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
                request.places());
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
    public JobAccepted createRouteJob(RecommendRequest request) {
        RecommendRequest normalized =
                withStage(request, "route", List.of(), request.places());
        JobAccepted accepted = agentClient.requestRecommend(normalized);
        jobStore.insertInProgress(accepted.jobId(), normalized.scheduleId());
        return accepted;
    }
}

package map.service.user.recommend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.recommend.dto.EditRequest;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.RecommendRequest;
import org.springframework.stereotype.Service;

/**
 * RecommendService — 추천 도메인 비즈니스 로직
 *
 * RecommendController 가 위임하는 추천 작업의 생성/조회/수정/재요청 흐름을 담당한다.
 * 외부 agent 호출은 AgentClient 로, draft 상태 보관/조회는 DraftStore 로,
 * draft JSON 의 부분 머지는 ObjectMapper 로 처리한다.
 *
 * agentClient: AgentClient. 추천 작업을 agent 서비스에 위임.
 * draftStore: DraftStore. draft JSON 상태 저장소(Redis 기반).
 * objectMapper: Jackson ObjectMapper. JSON 트리 파싱/직렬화에 사용.
 */
@Service
public class RecommendService {

    /** agent B2 계약의 exclude 목록 상한(초과분은 앞에서부터 절단). */
    private static final int EXCLUDE_MAX = 50;

    private final AgentClient agentClient;
    private final DraftStore draftStore;
    private final ObjectMapper objectMapper;
    private final ResearchLimitService researchLimitService;
    private final RecommendJobStore jobStore;

    public RecommendService(
            AgentClient agentClient,
            DraftStore draftStore,
            ObjectMapper objectMapper,
            ResearchLimitService researchLimitService,
            RecommendJobStore jobStore
    ) {
        this.agentClient = agentClient;
        this.draftStore = draftStore;
        this.objectMapper = objectMapper;
        this.researchLimitService = researchLimitService;
        this.jobStore = jobStore;
    }

    /**
     * 신규 추천 작업 생성.
     *
     * 클라이언트가 보낸 stage/exclude 는 신뢰하지 않고 서버측에서
     * stage="init", exclude=[] 로 강제 재구성한다(남용 방지 — exclude 는
     * Mode 1 재탐색 전용이며 research 가 draft 에서 구성한다).
     * AgentClient.requestRecommend 로 위임하여 agent 측에서 발급한
     * JobAccepted 를 반환한다. 본 단계에서는 draft 를 저장하지 않으며,
     * 완료 이벤트는 RecommendJobsConsumer 가 수신한다. 접수 직후 PG 에
     * in_progress 로 write-through 기록한다(best-effort, 실패해도 무시).
     *
     * request: 클라이언트에서 검증 완료된 RecommendRequest.
     */
    public JobAccepted createRecommendation(RecommendRequest request) {
        JobAccepted accepted = agentClient.requestRecommend(
                withStage(request, "init", List.of()));
        jobStore.insertInProgress(accepted.jobId(), request.scheduleId());
        return accepted;
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
     * 기존 draft 의 places[].content_id 를 수집해 exclude 목록을 만들고
     * (실측 근거 없는 항목은 content_id 가 없어 자연 제외), draft 를
     * 폐기한 뒤 stage="mode1" + exclude 로 재구성한 요청을 agent 에
     * 위임한다(SoT §6.2 exclude_list). draft 가 없거나 파싱 불가하면
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
        return new RecommendRequest(
                request.date(),
                request.budget(),
                request.theme(),
                request.mobility(),
                request.province(),
                request.city(),
                request.scheduleId(),
                stage,
                exclude);
    }
}

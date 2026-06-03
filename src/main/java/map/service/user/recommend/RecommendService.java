package map.service.user.recommend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
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

    private final AgentClient agentClient;
    private final DraftStore draftStore;
    private final ObjectMapper objectMapper;

    public RecommendService(
            AgentClient agentClient,
            DraftStore draftStore,
            ObjectMapper objectMapper
    ) {
        this.agentClient = agentClient;
        this.draftStore = draftStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 신규 추천 작업 생성.
     *
     * AgentClient.requestRecommend 로 위임하여 agent 측에서 발급한 JobAccepted 를 반환한다.
     * 본 단계에서는 draft 를 저장하지 않으며, 완료 이벤트는 RecommendJobsConsumer 가 수신한다.
     *
     * request: 클라이언트에서 검증 완료된 RecommendRequest.
     */
    public JobAccepted createRecommendation(RecommendRequest request) {
        return agentClient.requestRecommend(request);
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
     * 기존 jobId 의 draft 를 DraftStore.delete 로 폐기한 뒤
     * AgentClient.requestRecommend 로 신규 추천 작업을 생성한다.
     *
     * jobId: 폐기할 기존 작업 식별자.
     * request: 신규 추천에 사용할 RecommendRequest.
     */
    public JobAccepted research(String jobId, RecommendRequest request) {
        draftStore.delete(jobId);
        return agentClient.requestRecommend(request);
    }
}

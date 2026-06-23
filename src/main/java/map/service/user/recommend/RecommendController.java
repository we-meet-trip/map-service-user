package map.service.user.recommend;

import jakarta.validation.Valid;
import java.util.Optional;
import map.service.user.recommend.dto.EditRequest;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.RecommendRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RecommendController — 추천 도메인 HTTP 진입점
 *
 * /api/v1/recommend 경로 하위의 4개 엔드포인트를 노출한다.
 * 모든 비즈니스 동작은 RecommendService 로 위임하고, 본 클래스는 HTTP 계층의
 * 요청 매핑/응답 코드/헤더만 담당한다.
 *
 * service: RecommendService. 생성자 주입.
 *
 * 엔드포인트:
 * - POST /api/v1/recommend          → create
 * - GET  /api/v1/recommend/{jobId}  → get (long-poll)
 * - POST /api/v1/recommend/{jobId}/edit     → edit
 * - POST /api/v1/recommend/{jobId}/research → research
 */
@RestController
@RequestMapping("/api/v1/recommend")
public class RecommendController {

    private final RecommendService service;

    public RecommendController(RecommendService service) {
        this.service = service;
    }

    /**
     * 신규 추천 작업 생성.
     *
     * 클라이언트가 전달한 RecommendRequest 를 검증(@Valid)한 뒤
     * RecommendService.createRecommendation 으로 위임한다.
     * 응답은 202 Accepted + JobAccepted(job_id/status/retry_after_seconds).
     *
     * request: @Valid @RequestBody RecommendRequest. 본문 형식 위반 시 400.
     */
    @PostMapping
    public ResponseEntity<JobAccepted> create(
            @Valid @RequestBody RecommendRequest request
    ) {
        JobAccepted accepted = service.createRecommendation(request);
        return ResponseEntity.accepted().body(accepted);
    }

    /**
     * 추천 결과 조회 (long-poll).
     *
     * jobId 의 draft 가 준비된 경우 200 OK + JSON 본문을 그대로 반환한다.
     * 아직 준비되지 않은 경우 202 Accepted + Retry-After: 3 헤더만 내려보내
     * 클라이언트에게 재폴링을 유도한다.
     *
     * jobId: @PathVariable. 추천 작업 식별자.
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<String> get(@PathVariable String jobId) {
        Optional<String> draft = service.findDraft(jobId);
        if (draft.isPresent()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(draft.get());
        }
        return ResponseEntity.accepted()
                .header(HttpHeaders.RETRY_AFTER, "3")
                .build();
    }

    /**
     * 추천 draft 부분 수정 (places / visit_order / legs).
     *
     * jobId 에 해당하는 draft 가 존재하면 EditRequest 의 비-null 필드만
     * 기존 draft JSON 위에 shallow merge 하여 다시 저장하고 머지 결과를 반환한다.
     * draft 가 없거나 머지 대상 형식이 아니면 404.
     *
     * jobId: @PathVariable. 수정 대상 추천 작업.
     * edit: @RequestBody EditRequest. places/visit_order/legs 부분 갱신 데이터.
     */
    @PostMapping("/{jobId}/edit")
    public ResponseEntity<String> edit(
            @PathVariable String jobId,
            @RequestBody EditRequest edit
    ) {
        Optional<String> updated = service.applyEdit(jobId, edit);
        return updated
                .map(json -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 재추천 요청.
     *
     * 기존 jobId 의 draft 를 폐기하고 동일 요청 본문으로 새 추천 작업을 생성한다.
     * 응답은 create 와 동일하게 202 Accepted + JobAccepted.
     *
     * jobId: @PathVariable. 폐기 대상 기존 작업 식별자.
     * request: @Valid @RequestBody RecommendRequest. 새 추천 입력.
     */
    @PostMapping("/{jobId}/research")
    public ResponseEntity<JobAccepted> research(
            @PathVariable String jobId,
            @Valid @RequestBody RecommendRequest request
    ) {
        JobAccepted accepted = service.research(jobId, request);
        return ResponseEntity.accepted().body(accepted);
    }
}

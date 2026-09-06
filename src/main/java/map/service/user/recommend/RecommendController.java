package map.service.user.recommend;

import jakarta.validation.Valid;
import java.util.Optional;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.recommend.dto.EditRequest;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.RecommendRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
     * RecommendService.createRecommendationDetailed 로 위임한다.
     * 응답은 202 Accepted + JobAccepted(job_id/status/retry_after_seconds).
     * 재사용 캐시 히트/미스는 X-Recommend-Cache 헤더(HIT/MISS)로만 노출한다
     * (검증/관측 목적 — 응답 본문 계약은 캐시 히트와 무관하게 동일하다).
     *
     * request: @Valid @RequestBody RecommendRequest. 본문 형식 위반 시 400.
     * userId: 토큰이 실려 있고 유효할 때만 채워진다. 이 경로는 인증을
     *         요구하지 않으므로 비로그인 요청에서는 null 이며, 그때는
     *         저장된 취향 없이 기존과 동일하게 처리된다.
     */
    @PostMapping
    public ResponseEntity<JobAccepted> create(
            @Valid @RequestBody RecommendRequest request,
            @AuthenticationPrincipal Long userId
    ) {
        if (userId == null) throw new map.service.user.global.exception.CustomException(map.service.user.global.exception.ErrorCode.INVALID_TOKEN);
        RecommendService.RecommendationResult result =
                service.createRecommendationDetailed(request, userId);
        return ResponseEntity.accepted()
                .header("X-Recommend-Cache", result.cacheHit() ? "HIT" : "MISS")
                .body(result.accepted());
    }

    /**
     * 사용자가 고른 장소들의 동선 작업 생성.
     *
     * 장소를 이미 정해 온 요청이라 탐색·선정을 건너뛰고 동선만 짠다. 결과
     * 조회는 일반 추천과 같은 GET /{jobId} 를 쓴다 — 응답 형태가 같기 때문에
     * 클라이언트가 조회 코드를 나눌 필요가 없다.
     *
     * 재사용 캐시는 타지 않으므로 X-Recommend-Cache 헤더도 없다. 캐시 키는
     * 검색 조건으로 만들어지는데 이 요청의 본질은 "고른 장소 조합"이라
     * 조건이 같아도 결과가 달라진다.
     *
     * request: @Valid @RequestBody RecommendRequest. places 2~10개 필수 —
     *          없거나 1개면 400.
     */
    @PostMapping("/route")
    public ResponseEntity<JobAccepted> route(
            @Valid @RequestBody RecommendRequest request,
            @AuthenticationPrincipal Long userId
    ) {
        if (request.places() == null || request.places().size() < 2) {
            throw new IllegalArgumentException(
                    "places must contain 2 to 10 selected places");
        }
        if (userId == null) throw new CustomException(ErrorCode.INVALID_TOKEN);
        return ResponseEntity.accepted().body(service.createRouteJob(request, userId));
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
    public ResponseEntity<String> get(@PathVariable String jobId,
                                      @AuthenticationPrincipal Long userId) {
        Optional<String> draft = service.findOwnedDraft(jobId, userId);
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
     * edit: @Valid @RequestBody EditRequest. places 가 있으면 각 Place 좌표
     *       범위(33~43 / 124~132)를 검증하며, 위반 시 400.
     * userId: 토큰이 실려 있고 유효할 때만 채워진다. 잡에 소유자가 적혀 있는데
     *         이 값이 다르면 403 이다. 적혀 있지 않으면 막지 않는다.
     * idempotencyKey: 같은 요청의 재시도를 가려내는 선택 헤더. 망이 끊겨 다시
     *         보낸 것이 두 번의 수정으로 기록되지 않게 한다.
     */
    @PostMapping("/{jobId}/edit")
    public ResponseEntity<String> edit(
            @PathVariable String jobId,
            @Valid @RequestBody EditRequest edit,
            @AuthenticationPrincipal Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        Optional<String> updated = service.applyEdit(jobId, edit, userId, idempotencyKey);
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
            @Valid @RequestBody RecommendRequest request,
            @AuthenticationPrincipal Long userId
    ) {
        JobAccepted accepted = service.research(jobId, request, userId);
        return ResponseEntity.accepted().body(accepted);
    }
}

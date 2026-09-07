package map.service.user.places;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import map.service.user.places.dto.ReviewSearchResponse;
import map.service.user.places.dto.ReviewSummaryResponse;
import map.service.user.places.dto.ReviewSummaryRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ReviewSearchController — 리뷰 검색 도메인 HTTP 진입점 (경계 B1)
 *
 * client 가 검색어로 리뷰(블로그)를 직접 조회하는 엔드포인트를 노출한다.
 * 실제 조회는 ReviewSearchClient 를 통해 hub 로 위임하고, 본 클래스는
 * 요청 매핑만 담당한다.
 *
 * 엔드포인트:
 * - GET /api/v1/reviews → search
 * - GET /api/v1/reviews/summary → summary
 */
@RestController
@RequestMapping("/api/v1/reviews")
@Validated
public class ReviewSearchController {

    private final ReviewSearchClient client;
    private final ReviewSummaryService summaryService;
    private final ReviewSummaryGenerationService generationService;

    public ReviewSearchController(
            ReviewSearchClient client, ReviewSummaryService summaryService,
            ReviewSummaryGenerationService generationService
    ) {
        this.client = client;
        this.summaryService = summaryService;
        this.generationService = generationService;
    }

    /**
     * 리뷰 검색.
     *
     * query: 검색어(필수). 1~60자.
     * display: 결과 개수(선택). 1~10.
     * start: 조회 시작 위치(선택). 1 이상. 더보기를 누를 때마다 앞 구간
     *        길이를 더해 보내면 다음 구간이 온다.
     * sort: 정렬 기준(선택). "sim" 정확도 · "date" 최신순.
     */
    @GetMapping
    public ReviewSearchResponse search(
            @RequestParam @NotBlank @Size(max = 60) String query,
            @RequestParam(required = false) @Min(1) @Max(10) Integer display,
            @RequestParam(required = false) @Min(1) @Max(100) Integer start,
            @RequestParam(required = false)
            @Pattern(regexp = "sim|date", message = "sort must be sim or date")
            String sort
    ) {
        return client.search(query, display, start, sort);
    }

    /**
     * 장소 블로그 요약.
     *
     * 장소를 눌렀을 때 두 줄 요약을 받는다. 근거를 못 구하거나 요약에
     * 실패하면 빈 목록이 오며, 그 자체는 오류가 아니다 — 화면은 요약 영역만
     * 접고 블로그 목록은 그대로 보여준다.
     *
     * query: 장소명(필수). 1~60자.
     */
    @GetMapping("/summary")
    public ReviewSummaryResponse summary(
            @RequestParam @NotBlank @Size(max = 60) String query
    ) {
        return summaryService.cachedSummary(query);
    }

    /** Only an explicit, consented action may create a new generative summary. */
    @PostMapping("/summary")
    public ReviewSummaryResponse generateSummary(
            @Valid @RequestBody ReviewSummaryRequest request,
            @AuthenticationPrincipal Long userId) {
        return generationService.generate(userId, request);
    }
}

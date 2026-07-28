package map.service.user.places;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import map.service.user.places.dto.ReviewSearchResponse;
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
 */
@RestController
@RequestMapping("/api/v1/reviews")
@Validated
public class ReviewSearchController {

    private final ReviewSearchClient client;

    public ReviewSearchController(ReviewSearchClient client) {
        this.client = client;
    }

    /**
     * 리뷰 검색.
     *
     * query: 검색어(필수). 1~60자.
     * display: 결과 개수(선택). 1~10.
     */
    @GetMapping
    public ReviewSearchResponse search(
            @RequestParam @NotBlank @Size(max = 60) String query,
            @RequestParam(required = false) @Min(1) @Max(10) Integer display
    ) {
        return client.search(query, display);
    }
}

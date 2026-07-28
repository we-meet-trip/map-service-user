package map.service.user.places;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import map.service.user.places.dto.PlaceSearchResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PlaceSearchController — 장소 검색 도메인 HTTP 진입점 (경계 B1)
 *
 * client 가 행정구역/검색어로 장소를 직접 조회하는 엔드포인트를 노출한다.
 * 실제 조회는 PlaceSearchClient 를 통해 hub 로 위임하고, 본 클래스는
 * 요청 매핑만 담당한다.
 *
 * 엔드포인트:
 * - GET /api/v1/places/search → search
 */
@RestController
@RequestMapping("/api/v1/places")
@Validated
public class PlaceSearchController {

    private final PlaceSearchClient client;

    public PlaceSearchController(PlaceSearchClient client) {
        this.client = client;
    }

    /**
     * 장소 검색.
     *
     * province: 광역시도(필수). 예: "서울특별시"
     * city: 시군구(선택). 예: "강남구"
     * query: 검색어(선택). 없으면 hub 가 행정구역명을 검색어로 쓴다.
     * category: 카카오 카테고리 그룹 코드(선택).
     * mobility: 이동수단(선택). 코스 출처를 걷기/자전거로 거른다.
     * size: 출처별 최대 결과 수(선택).
     */
    @GetMapping("/search")
    public PlaceSearchResponse search(
            @RequestParam @NotBlank String province,
            @RequestParam(required = false, defaultValue = "") String city,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String mobility,
            @RequestParam(required = false) @Min(1) @Max(15) Integer size
    ) {
        return client.search(province, city, query, category, mobility, size);
    }
}

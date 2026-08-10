package map.service.user.places;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import map.service.user.places.dto.PlacePhotosResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PlacePhotosController — 장소 사진 도메인 HTTP 진입점 (경계 B1)
 *
 * client 가 장소명과 좌표로 사진을 조회하는 엔드포인트를 노출한다. 실제
 * 조회는 PlacePhotosClient 를 통해 hub 로 위임하고, 본 클래스는 요청 매핑만
 * 담당한다.
 *
 * 장소 검색(PlaceSearchController)과 클래스를 나눠 둔다. 사진 조회만
 * 외부에서 건당 과금이 붙어 호출 경로를 따로 추적해야 하기 때문이다.
 *
 * 엔드포인트:
 * - GET /api/v1/places/photos → photos
 */
@RestController
@RequestMapping("/api/v1/places")
@Validated
public class PlacePhotosController {

    private final PlacePhotosClient client;

    public PlacePhotosController(PlacePhotosClient client) {
        this.client = client;
    }

    /**
     * 장소 사진 조회.
     *
     * 사진을 못 찾으면 빈 목록이 오며, 그 자체는 오류가 아니다 — 화면은
     * 사진 영역만 접고 나머지를 그대로 보여준다.
     *
     * 받은 사진 URL 은 수명이 짧다. 화면에 띄울 때마다 다시 조회하고
     * 저장해 두지 않는다.
     *
     * query: 장소명(필수). 1~60자.
     * lat: 위도(필수). 국내 범위 33.0~43.0.
     * lng: 경도(필수). 국내 범위 124.0~132.0.
     */
    @GetMapping("/photos")
    public PlacePhotosResponse photos(
            @RequestParam @NotBlank @Size(max = 60) String query,
            @RequestParam @DecimalMin("33.0") @DecimalMax("43.0") double lat,
            @RequestParam @DecimalMin("124.0") @DecimalMax("132.0") double lng
    ) {
        return client.fetch(query, lat, lng);
    }
}

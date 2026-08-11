package map.service.user.mobility;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import map.service.user.mobility.dto.PmVehiclesResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PmVehicleController — 공유 킥보드 도메인 HTTP 진입점 (경계 B1)
 *
 * client 가 좌표 주변의 공유 킥보드를 조회하는 엔드포인트를 노출한다. 실제
 * 조회는 PmVehicleClient 를 통해 hub 로 위임하고, 본 클래스는 요청 매핑만
 * 담당한다.
 *
 * 대여소 조회와 클래스를 나눠 둔다. 둘 다 이동수단이지만 발급처가 다르고,
 * 킥보드 쪽은 사업자마다 따로 물어야 해서 한 번 조회에 나가는 외부 호출 수가
 * 다르다. 호출 경로를 따로 추적할 수 있게 나눠 둔다.
 *
 * 엔드포인트:
 * - GET /api/v1/mobility/pm-vehicles → pmVehicles
 */
@RestController
@RequestMapping("/api/v1/mobility")
@Validated
public class PmVehicleController {

    private final PmVehicleClient client;

    /** 반경을 지정하지 않았을 때 쓰는 기본값(m). 걸어가서 타는 거리다. */
    private static final int DEFAULT_RADIUS_M = 1000;

    public PmVehicleController(PmVehicleClient client) {
        this.client = client;
    }

    /**
     * 좌표 주변 공유 킥보드 조회.
     *
     * 조회에 실패해도 오류가 아니라 status 로 온다. 주변에 기기가 없으면 빈
     * 목록이며 그것도 정상이다.
     *
     * lat: 위도(필수). 국내 범위 33.0~43.0.
     * lng: 경도(필수). 국내 범위 124.0~132.0.
     * radiusM: 잘라 받을 반경(m). 100~20000, 기본 1000.
     * city: 시군구명(선택). 발급처가 지역으로 좁혀 받을 수 있다.
     */
    @GetMapping("/pm-vehicles")
    public PmVehiclesResponse pmVehicles(
            @RequestParam @DecimalMin("33.0") @DecimalMax("43.0") double lat,
            @RequestParam @DecimalMin("124.0") @DecimalMax("132.0") double lng,
            @RequestParam(defaultValue = "" + DEFAULT_RADIUS_M)
            @Min(100) @Max(20000) int radiusM,
            @RequestParam(required = false) @Size(max = 30) String city
    ) {
        return client.fetch(lat, lng, radiusM, city);
    }
}

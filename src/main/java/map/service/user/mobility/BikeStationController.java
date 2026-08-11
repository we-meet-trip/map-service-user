package map.service.user.mobility;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import map.service.user.mobility.dto.BikeStationsResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * BikeStationController — 대여소 도메인 HTTP 진입점 (경계 B1)
 *
 * client 가 좌표 주변의 따릉이 대여소를 조회하는 엔드포인트를 노출한다.
 * 실제 조회는 BikeStationClient 를 통해 hub 로 위임하고, 본 클래스는 요청
 * 매핑만 담당한다.
 *
 * 이 경로가 생기기 전에는 앱이 발급처를 직접 불렀다. 그래서 인증키가 앱에
 * 함께 실려 나갔고, 발급처가 https 를 받지 않아 사용자가 접속한 망마다
 * 그 키가 평문으로 오갔다. 둘 다 없애려고 조회를 이리로 옮겼다.
 *
 * 엔드포인트:
 * - GET /api/v1/mobility/bike-stations → bikeStations
 */
@RestController
@RequestMapping("/api/v1/mobility")
@Validated
public class BikeStationController {

    private final BikeStationClient client;

    /** 반경을 지정하지 않았을 때 쓰는 기본값(m). */
    private static final int DEFAULT_RADIUS_M = 5000;

    public BikeStationController(BikeStationClient client) {
        this.client = client;
    }

    /**
     * 좌표 주변 대여소 조회.
     *
     * 서비스 지역이 서울이라 그 밖 좌표로 물으면 빈 목록이 온다. 그것은
     * 오류가 아니므로 화면은 빈 지도를 그대로 보여주면 된다.
     *
     * lat: 위도(필수). 국내 범위 33.0~43.0.
     * lng: 경도(필수). 국내 범위 124.0~132.0.
     * radiusM: 잘라 받을 반경(m). 100~20000, 기본 5000.
     */
    @GetMapping("/bike-stations")
    public BikeStationsResponse bikeStations(
            @RequestParam @DecimalMin("33.0") @DecimalMax("43.0") double lat,
            @RequestParam @DecimalMin("124.0") @DecimalMax("132.0") double lng,
            @RequestParam(defaultValue = "" + DEFAULT_RADIUS_M)
            @Min(100) @Max(20000) int radiusM
    ) {
        return client.fetch(lat, lng, radiusM);
    }
}

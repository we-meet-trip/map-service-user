package map.service.user.transit;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import map.service.user.transit.dto.SubwayRouteResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SubwayRouteController — 지하철 경로 도메인 HTTP 진입점 (경계 B1)
 *
 * client 가 두 좌표로 지하철 경로를 조회하는 엔드포인트를 노출한다. 실제
 * 조회는 SubwayRouteClient 를 통해 hub 로 위임하고, 본 클래스는 요청 매핑만
 * 담당한다.
 *
 * 이 경로가 생기기 전에는 앱이 발급처를 직접 불렀고 그래서 인증키가 앱
 * 꾸러미에 함께 실려 나갔다. 키를 서버에만 두려고 조회를 이리로 옮겼다.
 *
 * 엔드포인트:
 * - GET /api/v1/transit/subway → subway
 */
@RestController
@RequestMapping("/api/v1/transit")
@Validated
public class SubwayRouteController {

    private final SubwayRouteClient client;

    public SubwayRouteController(SubwayRouteClient client) {
        this.client = client;
    }

    /**
     * 지하철 단독 경로 조회.
     *
     * 버스가 섞인 경로는 오지 않는다. 화면이 지하철 전용이라 섞인 경로를
     * 주면 안내와 실제가 어긋난다.
     *
     * 조회에 실패해도 오류가 아니라 status 로 온다. 화면은 그 값으로
     * "지하철만으로 갈 수 없음"과 "지금은 조회할 수 없음"을 갈라 보여준다.
     *
     * startLat / endLat: 위도(필수). 국내 범위 33.0~43.0.
     * startLng / endLng: 경도(필수). 국내 범위 124.0~132.0.
     */
    @GetMapping("/subway")
    public SubwayRouteResponse subway(
            @RequestParam @DecimalMin("33.0") @DecimalMax("43.0")
            double startLat,
            @RequestParam @DecimalMin("124.0") @DecimalMax("132.0")
            double startLng,
            @RequestParam @DecimalMin("33.0") @DecimalMax("43.0")
            double endLat,
            @RequestParam @DecimalMin("124.0") @DecimalMax("132.0")
            double endLng
    ) {
        return client.fetch(startLat, startLng, endLat, endLng);
    }
}

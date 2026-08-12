package map.service.user.transit;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import map.service.user.transit.dto.TransitRouteOptionsResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * TransitRouteController — 통합 길찾기 도메인 HTTP 진입점 (경계 B1)
 *
 * client 가 두 좌표로 대중교통 경로 후보를 조회하는 엔드포인트를 노출한다.
 * SubwayRouteController 와 같은 패턴이며, 실제 조회는 TransitRouteClient 를
 * 통해 hub 로 위임한다.
 *
 * SubwayRouteController(/subway) 는 지하철 단독 경로 하나만 돌려주는 반면,
 * 여기는 지하철·버스를 가리지 않고 소요시간 순으로 여러 후보를 나열한다 —
 * "이동수단을 모두 보여주는" 화면이 이 엔드포인트를 쓴다. 두 컨트롤러가
 * 같은 요청 경로 접두사(/api/v1/transit) 아래에서 서로 다른 세부 경로를
 * 맡는다.
 *
 * 엔드포인트:
 * - GET /api/v1/transit/routes → routes
 */
@RestController
@RequestMapping("/api/v1/transit")
@Validated
public class TransitRouteController {

    private final TransitRouteClient client;

    public TransitRouteController(TransitRouteClient client) {
        this.client = client;
    }

    /**
     * 대중교통 통합 경로 후보 조회.
     *
     * 지하철 전용·버스 전용·혼합 경로를 소요시간 순으로 모두 담아 돌려준다.
     *
     * 조회에 실패해도 오류가 아니라 status 로 온다. 화면은 그 값으로
     * "갈 수 있는 경로 없음"과 "지금은 조회할 수 없음"을 갈라 보여준다.
     *
     * startLat / endLat: 위도(필수). 국내 범위 33.0~43.0.
     * startLng / endLng: 경도(필수). 국내 범위 124.0~132.0.
     */
    @GetMapping("/routes")
    public TransitRouteOptionsResponse routes(
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

package map.service.user.transit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import map.service.user.transit.dto.TransitLaneRequest;
import map.service.user.transit.dto.TransitLaneResponse;
import map.service.user.transit.dto.TransitRouteOptionsResponse;
import map.service.user.transit.dto.TransitWalkRequest;
import map.service.user.transit.dto.TransitWalkResponse;
import map.service.user.trip.HubDirectionsClient;
import map.service.user.trip.dto.HubDirectionsDtos.LegReq;
import map.service.user.trip.dto.HubDirectionsDtos.Point;
import map.service.user.trip.dto.HubDirectionsDtos.Route;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
 * - POST /api/v1/transit/routes/lane → lane (후보 한 건의 실제 노선 좌표)
 * - POST /api/v1/transit/routes/walk → walk (구간 사이 도보 연결선의 보행 경로)
 */
@RestController
@RequestMapping("/api/v1/transit")
@Validated
public class TransitRouteController {

    /**
     * hub 도로 경로 조회는 구간마다 장소 이름을 요구한다. 역 이름을 넣지 않고
     * 이 표기로 채운다 — 이름이 있어야 할 이유가 없고, 넣으면 이동 경로가
     * hub 쪽 기록에 남는다.
     */
    private static final String WALK_LABEL = "도보";

    private final TransitRouteClient client;
    private final HubDirectionsClient directions;
    private final boolean walkEnabled;

    public TransitRouteController(
            TransitRouteClient client,
            HubDirectionsClient directions,
            @Value("${transit-walk.enabled:false}") boolean walkEnabled) {
        this.client = client;
        this.directions = directions;
        this.walkEnabled = walkEnabled;
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
     * mode: 화면이 고른 이동수단. all(기본)·subway·bus 셋만 받는다.
     *       무엇을 어떻게 거를지는 hub 가 정하고 여기서는 넘기기만 한다 —
     *       규칙이 두 곳에 흩어지면 한쪽만 고치는 일이 생긴다.
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
            double endLng,
            @RequestParam(defaultValue = "all")
            @Pattern(regexp = "all|subway|bus", message = "mode must be all, subway or bus")
            String mode
    ) {
        return client.fetch(startLat, startLng, endLat, endLng, mode);
    }

    /**
     * 경로 후보 한 건의 실제 노선 좌표 조회.
     *
     * routes 응답의 구간 좌표는 지나는 정류장을 직선으로 이은 것이다. client 가
     * 후보를 골라 지도를 열 때 그 후보의 mapObj 와 구간 종류(types)를 보내면,
     * 실제 선로·도로 굴곡을 따라가는 좌표를 types 와 같은 순서로 돌려준다.
     *
     * 본문으로 받는 이유: mapObj 에 타고 내린 구간이 담겨 있어 주소창(쿼리)에
     * 싣지 않는다. 형식 검사는 TransitLaneRequest 가 하고, 틀리면 400 이며
     * hub 를 부르지 않는다.
     *
     * 조회하지 못하면 오류가 아니라 status "unavailable" 로 온다 — client 는
     * 그때 이미 가진 정류장 직선을 그대로 그린다.
     */
    @PostMapping("/routes/lane")
    public TransitLaneResponse lane(@Valid @RequestBody TransitLaneRequest request) {
        return client.fetchLane(request);
    }

    /**
     * 경로 지도의 도보 연결선을 실제 보행 경로로 조회.
     *
     * 지도에서 구간 사이를 잇는 회색 선은 좌표가 없어 직선으로 그린 근사다.
     * 그 양 끝을 받아 hub 도로 경로 조회(도보)에 넘기고, 받은 보행 경로를 요청과
     * 같은 순서로 돌려준다. 새 hub 호출 코드를 만들지 않고 일정 경로가 쓰는
     * HubDirectionsClient 를 그대로 쓴다 — 좌표 감싸기·20개씩 나눠 보내기·
     * 실패 흡수가 이미 들어 있다.
     *
     * 플래그(transit-walk.enabled)가 꺼져 있으면 hub 를 부르지 않고
     * "unavailable". 한 구간이라도 받으면 "ok" 이고, 못 받은 자리는 빈 목록 —
     * client 는 그 연결선만 직선으로 둔다. 전부 못 받으면 "unavailable".
     */
    @PostMapping("/routes/walk")
    public TransitWalkResponse walk(@Valid @RequestBody TransitWalkRequest request) {
        if (!walkEnabled) {
            return TransitWalkResponse.unavailable();
        }
        List<LegReq> legs = request.segments().stream()
                .map(s -> new LegReq(
                        new Point(s.startLat(), s.startLng()),
                        new Point(s.endLat(), s.endLng()),
                        WALK_LABEL, WALK_LABEL))
                .toList();
        List<Route> routes = directions.fetchRoutes("walk", legs);
        if (routes == null) {
            // 전부 실패하면 목록 대신 null 이 온다(HubDirectionsClient 계약).
            return TransitWalkResponse.unavailable();
        }
        // 실제 엔진(OSRM) 결과만 쓴다. 스텁은 출발-도착을 등분한 가짜 직선이라
        // 지금 그린 회색 직선과 다를 게 없다 — 일정 경로(TripStopsAssembler)도
        // 같은 기준으로 OSRM 결과만 받는다.
        List<List<List<Double>>> paths = routes.stream()
                .map(r -> r == null || !"OSRM".equals(r.source())
                        || r.path() == null || r.path().size() < 2
                        ? List.<List<Double>>of()
                        : r.path())
                .toList();
        if (paths.stream().allMatch(List::isEmpty)) {
            return TransitWalkResponse.unavailable();
        }
        return new TransitWalkResponse("ok", paths);
    }
}

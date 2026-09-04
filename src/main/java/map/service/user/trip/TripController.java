package map.service.user.trip;

import jakarta.validation.Valid;
import map.service.user.trip.dto.TripGenerateRequest;
import map.service.user.trip.dto.TripGenerateResponse;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.trip.dto.TripReplanRequest;
import map.service.user.trip.dto.TripResearchRequest;
import map.service.user.trip.dto.TripRouteRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TripController — trip 생성 도메인 HTTP 진입점 (client 동기 계약)
 *
 * Flutter client 가 직접 호출하는 동기 단발 엔드포인트를 노출한다.
 * 모든 동작은 TripService 로 위임하고, 본 클래스는 매핑/검증만 담당한다.
 * 실패는 GlobalExceptionHandler 가 client 가 파싱하는 {error, message} 로 변환한다.
 *
 * 엔드포인트:
 * - POST /api/v1/trip/generate → 200 TripGenerateResponse (조건으로 일정 생성)
 * - POST /api/v1/trip/route    → 200 TripGenerateResponse (고른 장소로 동선 생성)
 * - POST /api/v1/trip/replan   → 200 TripGenerateResponse (저장된 일정 재추천)
 *
 * 두 응답 타입이 같은 이유는 결과 화면이 하나이기 때문이다 — 어느 쪽으로
 * 만들었든 client 는 같은 코드로 방문지와 동선을 그린다.
 */
@RestController
@RequestMapping("/api/v1/trip")
public class TripController {

    private final TripService service;

    public TripController(TripService service) {
        this.service = service;
    }

    /**
     * 여행 일정 동기 생성.
     *
     * 검증된 TripGenerateRequest 를 TripService 로 위임하여 완성된 일정을 200 으로 반환한다.
     * 본문 형식 위반 시 400, 추천 실패 시 502, 시간초과 시 504(전역 핸들러).
     *
     * request: @Valid @RequestBody TripGenerateRequest.
     * userId: 토큰이 실려 있고 유효할 때만 채워진다. 이 경로는 인증을
     *         요구하지 않으므로 비로그인 요청에서는 null 이며, 그때는
     *         저장된 취향 없이 기존과 동일하게 처리된다.
     */
    @PostMapping("/generate")
    public TripGenerateResponse generate(
            @Valid @RequestBody TripGenerateRequest request,
            @AuthenticationPrincipal Long userId
    ) {
        return service.generate(request, userId);
    }

    /**
     * 사용자가 고른 장소들로 동선 동기 생성.
     *
     * 장소를 이미 정해 온 요청이라 후보 탐색·선정을 건너뛴다. 응답은
     * generate 와 같은 형태이므로 결과 화면을 그대로 재사용한다.
     * 실패 코드도 generate 와 같다(400/502/504).
     *
     * request: @Valid @RequestBody TripRouteRequest. places 2~10개.
     */
    @PostMapping("/route")
    public TripGenerateResponse route(
            @Valid @RequestBody TripRouteRequest request
    ) {
        return service.route(request);
    }

    /**
     * 같은 조건에서 다른 장소로 재탐색(동기).
     *
     * 방금 받은 추천이 마음에 들지 않을 때 부른다. 조건은 그대로 다시 싣고,
     * 이전 추천의 장소는 제외 목록으로 실어 보낸다. keep 을 함께 보내면 그
     * 장소는 남고 나머지 자리만 새로 채워진다.
     *
     * generate 와 달리 재사용 캐시를 타지 않으며 하루 재탐색 한도를 깎는다 —
     * 한도를 넘기면 409 다. 나머지 실패 코드는 generate 와 같다(400/502/504).
     *
     * request: @Valid @RequestBody TripResearchRequest.
     */
    @PostMapping("/research")
    public TripGenerateResponse research(
            @Valid @RequestBody TripResearchRequest request
    ) {
        return service.research(request);
    }

    /**
     * 저장된 일정을 지금 날씨로 다시 짜서 완성된 일정을 돌려준다(날씨 배너의
     * "다시 추천받기").
     *
     * 응답이 generate 와 같은 형태라 결과 화면을 그대로 재사용한다. 조건은
     * 저장된 일정에서 가져오므로 본문에는 식별자만 싣는다.
     *
     * 재탐색(mode1)이 아니라 일반 추천 경로라 1일 3회 한도를 깎지 않는다.
     * 지역을 모르는 옛 일정과 이미 지나간 일정은 409, 남의 일정은 404,
     * 추천 실패는 502, 시간초과는 504(전역 핸들러).
     */
    @PostMapping("/replan")
    public TripGenerateResponse replan(
            @Valid @RequestBody TripReplanRequest request,
            @AuthenticationPrincipal Long userId
    ) {
        if (userId == null) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }
        return service.replan(request.scheduleId(), userId);
    }
}

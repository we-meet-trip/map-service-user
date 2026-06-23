package map.service.user.trip;

import jakarta.validation.Valid;
import map.service.user.trip.dto.TripGenerateRequest;
import map.service.user.trip.dto.TripGenerateResponse;
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
 * - POST /api/v1/trip/generate → 200 TripGenerateResponse
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
     */
    @PostMapping("/generate")
    public TripGenerateResponse generate(
            @Valid @RequestBody TripGenerateRequest request
    ) {
        return service.generate(request);
    }
}

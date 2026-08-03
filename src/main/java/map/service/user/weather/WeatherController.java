package map.service.user.weather;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import map.service.user.weather.dto.WeatherHomeResponse;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * WeatherController — 날씨 도메인 HTTP 진입점 (경계 B1)
 *
 * 홈 화면이 위치만 넘기면 카드에 필요한 값을 한 번에 받을 수 있게 한다.
 * 실제 조회는 WeatherHomeService 로 위임하고 본 클래스는 요청 매핑과 좌표
 * 범위 검증만 담당한다.
 *
 * 좌표 범위를 여기서 먼저 거르는 이유는, 국내 밖 좌표로 hub 를 왕복해 봐야
 * 예보 자체가 없어 실패하기 때문이다. 화면은 위치를 못 얻거나 범위를 벗어난
 * 경우 기본 좌표로 갈음해 호출한다.
 *
 * 엔드포인트:
 * - GET /api/v1/weather/home → home
 */
@RestController
@RequestMapping("/api/v1/weather")
@Validated
public class WeatherController {

    private final WeatherHomeService service;

    public WeatherController(WeatherHomeService service) {
        this.service = service;
    }

    /**
     * 현재 위치 기준 홈 카드 날씨.
     *
     * lat/lng: 기기 위치. 국내 범위 밖이면 400.
     */
    @GetMapping("/home")
    public WeatherHomeResponse home(
            @RequestParam @DecimalMin("33.0") @DecimalMax("43.0") double lat,
            @RequestParam @DecimalMin("124.0") @DecimalMax("132.0") double lng
    ) {
        return service.fetchHome(lat, lng);
    }
}

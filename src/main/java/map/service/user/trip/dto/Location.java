package map.service.user.trip.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Location — trip 생성 요청의 지역 (client Step 5)
 *
 * TripGenerateRequest 의 location 필드로 중첩되어 사용된다.
 * province/city 는 그대로 agent RecommendRequest 및 hub /v1/weather 로 전달된다.
 *
 * province: 광역시도(예: "강원특별자치도"). @NotBlank, 1~20자.
 * city:     시/군/구(예: "속초시").       @NotBlank, 1~20자.
 */
public record Location(
        @NotBlank @Size(min = 1, max = 20) String province,
        @NotBlank @Size(min = 1, max = 20) String city
) {
}

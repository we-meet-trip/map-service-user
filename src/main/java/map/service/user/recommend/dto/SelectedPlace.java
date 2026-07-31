package map.service.user.recommend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * SelectedPlace — 사용자가 직접 고른 방문지 1건
 *
 * 지도에서 장소를 골라 동선만 요청할 때 쓰는 입력. 좌표가 동선 계산의
 * 근거이므로 필수이고, 국내 범위를 벗어나면 거부한다(agent 쪽 검증과 같은
 * 범위 — 여기서 먼저 걸러 agent 왕복을 아낀다).
 *
 * name: 장소명. 1~80자. 꺾쇠(<, >)를 거부한다 — 뒤이어 이 값을 받는 쪽이
 *       같은 문자를 거부하므로, 여기서 막지 않으면 사용자 입력 오류가
 *       상류 서비스 오류(502)로 둔갑해 원인을 알 수 없게 된다.
 * address: 주소. 표시용이라 없어도 된다(생략하면 빈 문자열로 취급된다).
 * lat / lng: 좌표. 33~43 / 124~132.
 * contentId: 실측 출처 식별자. JSON key "content_id". 있으면 리뷰 조회에
 *            그대로 쓰이고, 없으면 이름으로 조회한다.
 */
public record SelectedPlace(
        @NotBlank
        @Size(min = 1, max = 80)
        @Pattern(regexp = "[^<>]*", message = "name must not contain < or >")
        String name,
        @Size(max = 200) String address,
        @DecimalMin("33.0") @DecimalMax("43.0") double lat,
        @DecimalMin("124.0") @DecimalMax("132.0") double lng,
        @JsonProperty("content_id") @Size(max = 64) String contentId
) {
}

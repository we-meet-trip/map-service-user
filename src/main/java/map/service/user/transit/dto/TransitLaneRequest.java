package map.service.user.transit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * TransitLaneRequest — 경로 후보 한 건의 실제 노선 좌표 요청 (client 계약)
 *
 * client 가 보내는 본문을 hub POST /v1/transit/routes/lane 에 그대로 넘긴다.
 *
 * mapObj: TransitRouteOption.mapObj 를 그대로. 쿼리가 아니라 본문으로 받는
 *   이유 — 노선·정류장 구간이 담겨 있어 어디서 타고 내리는지가 드러난다.
 *   좌표를 주소창에 싣지 않는 것과 같은 이유다. 발급처 값은 숫자·콜론·@ 뿐이라
 *   그 밖의 문자는 받지 않는다.
 * types: 그 후보의 legs[].type 을 순서 그대로. 응답 geometries 가 이 순서와
 *   1:1 로 맞춰진다. 허용 값은 hub 의 구간 종류와 같다.
 */
public record TransitLaneRequest(
        @JsonProperty("map_obj")
        @NotBlank @Size(max = 500) @Pattern(regexp = "[0-9:@]+")
        String mapObj,
        @NotEmpty @Size(max = 40)
        List<@Pattern(regexp = "walk|subway|bus|express|intercity|train|air")
                String> types
) {
}

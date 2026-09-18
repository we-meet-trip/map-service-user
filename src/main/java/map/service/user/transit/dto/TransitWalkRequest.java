package map.service.user.transit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * TransitWalkRequest — 대중교통 지도의 도보 연결선 요청 (client 계약)
 *
 * 경로 지도에서 구간 사이를 잇는 회색 선(출발지→첫 역, 환승 걷기, 마지막 역→
 * 도착지)의 양 끝 좌표를 받는다. 좌표라서 주소창이 아니라 본문으로 받는다.
 *
 * segments: 1~20개. hub 도로 경로 조회가 한 번에 받는 구간 수와 같다 —
 *   한 경로의 연결선은 많아야 대여섯 개라 넉넉하다. 좌표는 국내 범위만 받는다.
 */
public record TransitWalkRequest(
        @NotEmpty @Size(max = 20)
        List<@Valid @NotNull Segment> segments
) {

    /** 도보 연결선 하나의 양 끝. */
    public record Segment(
            @JsonProperty("start_lat") @DecimalMin("33.0") @DecimalMax("43.0")
            double startLat,
            @JsonProperty("start_lng") @DecimalMin("124.0") @DecimalMax("132.0")
            double startLng,
            @JsonProperty("end_lat") @DecimalMin("33.0") @DecimalMax("43.0")
            double endLat,
            @JsonProperty("end_lng") @DecimalMin("124.0") @DecimalMax("132.0")
            double endLng
    ) {
    }
}

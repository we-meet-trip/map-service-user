package map.service.user.transit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * SubwayRoute — 지하철 단독 경로 한 건 (client 계약)
 *
 * hub /v1/transit/subway 응답의 경로를 그대로 받아 client 로 전달한다.
 * 필드명은 hub 응답 키와 일치시킨다.
 *
 * totalTimeMin: 총 소요 시간(분). fare: 요금(원).
 * transferCount: 환승 횟수. totalWalkM: 총 도보 거리(m).
 * steps: 구간 목록(출발 순서).
 */
public record SubwayRoute(
        @JsonProperty("total_time_min") int totalTimeMin,
        int fare,
        @JsonProperty("transfer_count") int transferCount,
        @JsonProperty("total_walk_m") int totalWalkM,
        List<SubwayRouteStep> steps
) {
}

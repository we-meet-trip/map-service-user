package map.service.user.transit.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * TransitRouteOption — 통합 길찾기 경로 후보 한 건 (client 계약)
 *
 * hub /v1/transit/routes 응답의 경로 후보를 그대로 받아 client 로 전달한다.
 * SubwayRoute 와 달리 지하철 단독으로 거르지 않은 후보다.
 *
 * totalTimeMin: 총 소요 시간(분). fare: 요금(원).
 * transferCount: 환승 횟수. totalWalkM: 총 도보 거리(m).
 * modes: 이 경로에 실제 등장하는 이동수단(지하철·버스) 목록.
 * legs: 구간 목록(출발 순서).
 */
public record TransitRouteOption(
        @JsonProperty("total_time_min") int totalTimeMin,
        int fare,
        @JsonProperty("transfer_count") int transferCount,
        @JsonProperty("total_walk_m") int totalWalkM,
        @JsonProperty("subway_distance_m") int subwayDistanceM,
        @JsonProperty("bus_distance_m") int busDistanceM,
        @JsonProperty("bus_distance_ratio") double busDistanceRatio,
        List<String> modes,
        List<TransitRouteLeg> legs
) {
}

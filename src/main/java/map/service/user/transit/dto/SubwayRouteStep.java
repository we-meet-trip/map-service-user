package map.service.user.transit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SubwayRouteStep — 지하철 경로의 한 구간 (client 계약)
 *
 * hub /v1/transit/subway 의 구간 항목을 그대로 받아 client 로 전달한다.
 * 필드명은 hub 응답 키와 일치시킨다.
 *
 * type: 이동 방식. walk|subway|bus.
 * lineName: 노선명. 걷는 구간에는 없다.
 * startName / endName: 구간 양 끝 이름(역명 또는 출발지·도착지).
 * sectionTimeMin: 이 구간 소요 시간(분).
 * stationCount: 지나는 역 수. 걷는 구간에는 없다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubwayRouteStep(
        String type,
        @JsonProperty("line_name") String lineName,
        @JsonProperty("start_name") String startName,
        @JsonProperty("end_name") String endName,
        @JsonProperty("section_time_min") int sectionTimeMin,
        @JsonProperty("station_count") Integer stationCount
) {
}

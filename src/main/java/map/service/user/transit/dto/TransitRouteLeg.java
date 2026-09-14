package map.service.user.transit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * TransitRouteLeg — 통합 길찾기 경로 후보의 한 구간 (client 계약)
 *
 * hub /v1/transit/routes 의 구간 항목을 그대로 받아 client 로 전달한다.
 * SubwayRouteStep 과 필드가 같되 geometry 가 더 있다.
 *
 * type: 이동 방식. walk|subway|bus.
 * lineName: 노선명. 걷는 구간에는 없다.
 * startName / endName: 구간 양 끝 이름(역명 또는 출발지·도착지).
 * sectionTimeMin: 이 구간 소요 시간(분).
 * stationCount: 지나는 역 수. 걷는 구간에는 없다.
 * geometry: 지도에 그릴 [lat,lng] 좌표열. 좌표가 없는 순수 도보 연결
 *   구간은 빈 리스트다.
 * stops: 지나는 역/정류장 이름 목록(순서대로). geometry 와 같은 이유로
 *   비어 있을 수 있다.
 * mapObj: hub가 ODsay loadLane 조회에 그대로 되돌려줘야 하는 원본 토큰.
 *   걷는 구간과, hub가 아직 노선 좌표 조회를 지원하지 않는 시외·고속버스
 *   구간에는 없다(null). client가 이 값을 그대로 되돌려 실제 노선 좌표를
 *   따로 조회하는 데 쓴다 — 후보 목록 단계에서 구간마다 조회하면 hub의
 *   ODsay 일일 호출 상한을 빨리 소진하므로, 화면을 열 때만 쓴다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransitRouteLeg(
        String type,
        @JsonProperty("line_name") String lineName,
        @JsonProperty("start_name") String startName,
        @JsonProperty("end_name") String endName,
        @JsonProperty("section_time_min") int sectionTimeMin,
        @JsonProperty("station_count") Integer stationCount,
        @JsonProperty("distance_m") int distanceM,
        List<List<Double>> geometry,
        List<String> stops,
        @JsonProperty("map_obj") String mapObj
) {
}

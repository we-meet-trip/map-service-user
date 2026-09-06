package map.service.user.trip.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * HubDirectionsDtos — hub POST /v1/directions/batch 요청/응답 DTO 모음 (경계 B3)
 *
 * BFF 가 stop 간 이동 구간의 도로 추종 경로를 hub 에 일괄 요청/수신하는
 * 계약. 한 곳에 모아 두어(중첩 record) 계약 변경을 한눈에 본다.
 *
 * 좌표는 lat/lng(위도/경도)로 통일한다. hub 가 OSRM 의 [lng,lat] 을 이미
 * 스왑해 돌려주므로 BFF·client 는 전 구간 lat/lng 만 다룬다.
 */
public final class HubDirectionsDtos {

    private HubDirectionsDtos() {
    }

    /** 요청 본문: 이동수단(mode) + 구간 목록(legs). */
    /**
     * 구간 묶음 요청.
     *
     * loc 에 구간 전체를 감싸 담는다. 구간에는 좌표뿐 아니라 방문지 이름도
     * 들어가는데, 이름만으로도 어디를 다니는지가 드러나므로 함께 감싼다.
     * legs 는 감싸지 않던 예전 형태이며, 감쌀 때는 비워 보낸다 — 두 곳에
     * 같은 값을 실으면 감싼 의미가 없다.
     */
    public record BatchRequest(String mode, String loc, List<LegReq> legs) {
    }

    /** 한 구간 요청: 출발/도착 좌표 + 표시용 명칭. */
    public record LegReq(
            Point start,
            Point goal,
            @JsonProperty("start_name") String startName,
            @JsonProperty("goal_name") String goalName
    ) {
    }

    /** 좌표(위도/경도). */
    public record Point(double lat, double lng) {
    }

    /**
     * 응답 본문: routes 는 요청 legs 와 같은 길이·인덱스. 실패 구간은 null.
     */
    public record BatchResponse(List<Route> routes) {
    }

    /**
     * 한 구간 경로 결과.
     *
     * path: [lat,lng] 점 목록. distanceM: 실측 거리(m). durationS: 실측 시간(초).
     */
    public record Route(
            List<List<Double>> path,
            @JsonProperty("distance_m") int distanceM,
            @JsonProperty("duration_s") int durationS,
            String source,
            @JsonProperty("route_profile") String routeProfile
    ) {
        public Route(List<List<Double>> path, int distanceM, int durationS) {
            this(path, distanceM, durationS, "UNKNOWN", null);
        }
    }
}

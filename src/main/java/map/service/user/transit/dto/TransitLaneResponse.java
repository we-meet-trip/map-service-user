package map.service.user.transit.dto;

import java.util.List;

/**
 * TransitLaneResponse — 실제 노선 좌표 응답 (client 계약)
 *
 * hub POST /v1/transit/routes/lane 응답을 그대로 전달한다.
 *
 * status: "ok" 면 geometries 중 하나 이상이 채워져 있다. "unavailable" 은
 *   꺼짐·조회 불가·짝이 안 맞음을 모두 뜻한다 — client 가 하는 일은 셋 다
 *   같다(기존 정류장 직선 유지).
 * geometries: 요청 types 와 같은 길이·순서의 [lat,lng] 좌표열 목록. 빈
 *   리스트인 자리는 "그 구간은 원래 좌표를 그대로 쓰라"는 뜻이다.
 */
public record TransitLaneResponse(
        String status,
        List<List<List<Double>>> geometries
) {

    /** 조회하지 못했을 때 client 에 줄 값. 기존 직선을 그대로 쓰게 한다. */
    public static TransitLaneResponse unavailable() {
        return new TransitLaneResponse("unavailable", List.of());
    }
}

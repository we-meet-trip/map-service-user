package map.service.user.transit.dto;

import java.util.List;

/**
 * TransitWalkResponse — 도보 연결선의 실제 보행 경로 (client 계약)
 *
 * status: "ok" 면 paths 중 하나 이상이 채워져 있다. "unavailable" 은 꺼짐·조회
 *   불가를 모두 뜻한다 — client 가 하는 일은 같다(회색 직선 유지).
 * paths: 요청 segments 와 같은 길이·순서의 [lat,lng] 좌표열 목록. 빈 리스트인
 *   자리는 그 연결선을 직선 그대로 두라는 뜻이다.
 */
public record TransitWalkResponse(
        String status,
        List<List<List<Double>>> paths
) {

    /** 조회하지 못했을 때 client 에 줄 값. 회색 직선을 그대로 쓰게 한다. */
    public static TransitWalkResponse unavailable() {
        return new TransitWalkResponse("unavailable", List.of());
    }
}

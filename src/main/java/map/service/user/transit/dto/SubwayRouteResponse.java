package map.service.user.transit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * SubwayRouteResponse — 지하철 경로 조회 응답 (client 계약)
 *
 * hub /v1/transit/subway 응답을 그대로 받아 client 로 전달한다.
 *
 * status: 조회 결과 구분.
 *   "ok"          — 경로를 찾았다. route 가 채워진다.
 *   "not_found"   — 지하철만으로 갈 수 있는 경로가 없다. route 는 null.
 *   "unavailable" — 외부 조회에 실패했거나 하루 한도를 넘겼다. route 는 null.
 * route: status 가 "ok" 일 때만 채워진다.
 *
 * 화면은 "경로 없음"과 "조회 불가"를 다른 문구로 보여야 한다. 둘을 한 값으로
 * 합치면 외부 장애가 "갈 수 있는 길이 없다"로 표시되어 사용자가 잘못된
 * 결론을 얻는다. 그래서 조회 실패를 오류 응답이 아니라 이 값으로 전달한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubwayRouteResponse(
        String status,
        SubwayRoute route
) {
}

package map.service.user.transit.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * TransitRouteOptionsResponse — 통합 길찾기 조회 응답 (client 계약)
 *
 * hub /v1/transit/routes 응답을 그대로 받아 client 로 전달한다.
 *
 * status: SubwayRouteResponse 와 같은 세 값("ok"/"not_found"/"unavailable").
 * routes: status 가 "ok" 일 때 채워진다. 소요시간 오름차순.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransitRouteOptionsResponse(
        String status,
        List<TransitRouteOption> routes
) {
}

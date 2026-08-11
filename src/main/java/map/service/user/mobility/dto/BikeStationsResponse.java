package map.service.user.mobility.dto;

import java.util.List;

/**
 * BikeStationsResponse — 따릉이 대여소 조회 응답 (client 계약)
 *
 * hub /v1/mobility/bike-stations 응답을 그대로 받아 client 로 전달한다.
 *
 * status: 조회 결과 구분.
 *   "ok"          — 조회에 성공했다. 주변에 대여소가 없으면 빈 목록이며
 *                   그것도 정상이다(서비스 지역 밖).
 *   "unavailable" — 외부 조회에 실패했다. stations 는 빈 목록.
 * stations: 요청 좌표 주변의 대여소.
 * count: stations 길이.
 */
public record BikeStationsResponse(
        String status,
        List<BikeStation> stations,
        int count
) {
}

package map.service.user.mobility.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * BikeStation — 따릉이 대여소 한 곳 (client 계약)
 *
 * hub /v1/mobility/bike-stations 의 대여소 항목을 그대로 받아 client 로
 * 전달한다. 필드명은 hub 응답 키와 일치시킨다.
 *
 * stationId: 대여소 식별자. name: 대여소 이름.
 * rackTotal: 거치대 수. parkingBikeTotal: 지금 세워져 있는 자전거 수.
 * lat / lng: 대여소 좌표.
 */
public record BikeStation(
        @JsonProperty("station_id") String stationId,
        String name,
        @JsonProperty("rack_total") int rackTotal,
        @JsonProperty("parking_bike_total") int parkingBikeTotal,
        double lat,
        double lng
) {
}

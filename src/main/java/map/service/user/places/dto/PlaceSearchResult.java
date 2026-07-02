package map.service.user.places.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PlaceSearchResult — 장소 검색 결과 한 건 (client 계약)
 *
 * hub /v1/places 의 장소 항목을 그대로 받아 client 로 전달한다. 점 장소와
 * 코스를 한 형태로 담으며, 출처마다 의미 있는 필드만 채워지고 나머지는
 * null 이라 직렬화에서 생략된다.
 *
 * contentId/source/name/address/lat/lng: 공통 식별·표시 정보.
 * roadAddress/category/distanceM: 부가 표시/정렬 정보.
 * categoryGroupCode/phone/placeUrl: 점 장소(카카오) 전용.
 * crsDstncKm/crsTotalMin/crsLevel/brdDiv/gpxUrl/routeIdx: 코스 전용 메타.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlaceSearchResult(
        @JsonProperty("content_id") String contentId,
        String source,
        String name,
        String address,
        @JsonProperty("road_address") String roadAddress,
        double lat,
        double lng,
        String category,
        @JsonProperty("distance_m") Integer distanceM,
        @JsonProperty("category_group_code") String categoryGroupCode,
        String phone,
        @JsonProperty("place_url") String placeUrl,
        @JsonProperty("crs_dstnc_km") Double crsDstncKm,
        @JsonProperty("crs_total_min") Integer crsTotalMin,
        @JsonProperty("crs_level") Integer crsLevel,
        @JsonProperty("brd_div") String brdDiv,
        @JsonProperty("gpx_url") String gpxUrl,
        @JsonProperty("route_idx") String routeIdx
) {
}

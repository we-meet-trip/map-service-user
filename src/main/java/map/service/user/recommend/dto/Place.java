package map.service.user.recommend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Place — 추천 응답/수정의 장소 항목
 *
 * RecommendResponse.places 와 EditRequest.places 의 원소 타입.
 * 외부 장소 식별자, 표시 정보, 좌표, 권장 체류 시간을 담는다.
 *
 * placeId: 외부 장소 식별자(int). JSON key "place_id".
 * day: 여행 일차(1부터). agent 가 배정한 값을 그대로 전달받으며,
 *      TripService.toStops 가 TripStop.day 로 그대로 넘긴다.
 * name: 장소명.
 * address: 주소 문자열.
 * lat: 위도.
 * lng: 경도.
 * recommendedVisitTime: 권장 체류 시간 문자열. JSON key "recommended_visit_time".
 */
public record Place(
        @JsonProperty("place_id") int placeId,
        int day,
        String name,
        String address,
        double lat,
        double lng,
        @JsonProperty("recommended_visit_time") String recommendedVisitTime
) {
}

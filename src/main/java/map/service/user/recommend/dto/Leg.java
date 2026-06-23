package map.service.user.recommend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Leg — 두 장소 간 이동 구간
 *
 * 한 장소에서 다음 장소로의 이동 정보를 표현한다.
 * RecommendResponse.legs / EditRequest.legs 의 원소 타입.
 *
 * fromPlaceId: 출발 장소 id. JSON key "from".
 * toPlaceId:   도착 장소 id. JSON key "to".
 * mode: 이동수단(Mobility).
 * estimatedDistanceKm: 추정 이동 거리(km). JSON key "estimated_distance_km".
 * estimatedDurationMin: 추정 이동 시간(분). JSON key "estimated_duration_min".
 */
public record Leg(
        @JsonProperty("from") int fromPlaceId,
        @JsonProperty("to") int toPlaceId,
        Mobility mode,
        @JsonProperty("estimated_distance_km") double estimatedDistanceKm,
        @JsonProperty("estimated_duration_min") int estimatedDurationMin
) {
}

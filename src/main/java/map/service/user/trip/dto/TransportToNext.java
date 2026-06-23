package map.service.user.trip.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * TransportToNext — 한 stop 에서 다음 stop 으로의 이동 카드
 *
 * TripStop.transportToNext 로 중첩되며, 마지막 stop 에서는 null 이다.
 * agent 가 LLM 으로 추정한 구간(legs)에서 거리/시간을 가져오고, type/label 은
 * client 가 보낸 원본 transport 를 기준으로 표시한다(결정 D-7/D-8).
 *
 * type: 이동수단 코드. client 원본 transport(bicycle/scooter/walk/bus) 그대로.
 * label: 사람이 읽는 라벨(예: "이동: 자전거"). TripMapping.transportLabel.
 * durationMinutes: 추정 이동 시간(분). JSON key "duration_minutes". legs.estimated_duration_min.
 * distanceKm: 추정 이동 거리(km). JSON key "distance_km". legs.estimated_distance_km.
 */
public record TransportToNext(
        String type,
        String label,
        @JsonProperty("duration_minutes") int durationMinutes,
        @JsonProperty("distance_km") double distanceKm
) {
}

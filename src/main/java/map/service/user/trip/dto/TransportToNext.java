package map.service.user.trip.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * TransportToNext — 한 stop 에서 다음 stop 으로의 이동 카드
 *
 * TripStop.transportToNext 로 중첩되며, 마지막 stop 에서는 null 이다.
 * type/label 은 client 가 보낸 원본 transport 를 기준으로 표시한다(결정 D-7/D-8).
 * durationMinutes/distanceKm 는 hub 경로 조회가 성공하면 OSRM 실측값으로 대체되고,
 * 실패(또는 bus)면 agent LLM 추정값(legs)을 유지한다.
 *
 * type: 이동수단 코드. client 원본 transport(bicycle/scooter/walk/bus) 그대로.
 * label: 사람이 읽는 라벨(예: "이동: 자전거"). TripMapping.transportLabel.
 * durationMinutes: 이동 시간(분). JSON key "duration_minutes". 실측 대체 또는 LLM 추정.
 * distanceKm: 이동 거리(km). JSON key "distance_km". 실측 대체 또는 LLM 추정.
 * path: 도로 추종 폴리라인 [[lat,lng],...]. 경로 조회 성공 시에만 채워지며,
 *       없으면(null) JSON 에서 생략되어 client 가 직선 폴백한다(JSON key "path").
 */
public record TransportToNext(
        String type,
        String label,
        @JsonProperty("duration_minutes") int durationMinutes,
        @JsonProperty("distance_km") double distanceKm,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        List<List<Double>> path
) {
}

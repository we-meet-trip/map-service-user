package map.service.user.trip.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * TripGenerateResponse — POST /api/v1/trip/generate 의 200 응답 본문
 *
 * client 의 TripGenerateResponse.fromJson 과 정확히 1:1 인 동기 응답.
 * agent 의 추천 draft(places/visit_order/legs)와 hub 날씨를 접어 만든다.
 *
 * tripId: 추천 작업 식별자. JSON key "trip_id". agent job_id 를 그대로 사용.
 * totalDurationMinutes: 총 이동 시간(분). JSON key "total_duration_minutes".
 *                       legs.estimated_duration_min 의 합(R-1).
 * stops: 방문지 리스트(order 오름차순).
 * weatherForecast: 일자별 날씨. JSON key "weather_forecast". 데이터 없으면 빈 배열.
 * warnings: 추천 과정에서 반영하지 못한 조건 안내 문장 목록. draft 의 값을
 *           그대로 전달하며, 없으면 키 자체를 내보내지 않는다.
 * timelineStatus: 방문 시각 계산 상태("ok"|"trimmed"|"unverified"). 없으면
 *                 키를 내보내지 않는다. JSON key "timeline_status".
 */
public record TripGenerateResponse(
        @JsonProperty("trip_id") String tripId,
        @JsonProperty("total_duration_minutes") int totalDurationMinutes,
        List<TripStop> stops,
        @JsonProperty("weather_forecast") List<WeatherForecastItem> weatherForecast,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<String> warnings,
        @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("timeline_status") String timelineStatus
) {
}

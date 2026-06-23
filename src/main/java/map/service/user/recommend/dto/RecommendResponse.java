package map.service.user.recommend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * RecommendResponse — 추천 결과 응답
 *
 * 추천 작업 완료 시 draft 로 저장되어 클라이언트의 결과 폴링으로 전달되는 형식.
 * 진행 중/실패 상태에서는 places/visitOrder/legs 가 비거나 error/retryAfterSeconds 가 채워진다.
 *
 * jobId: 작업 식별자. JSON key "job_id".
 * status: 작업 상태 문자열.
 * places: 추천된 장소 목록.
 * visitOrder: 장소 방문 순서. place_id 의 정렬. JSON key "visit_order".
 * legs: 장소 간 이동 구간 목록.
 * error: 오류 메시지(있을 때).
 * retryAfterSeconds: 재시도 권장 대기 시간. JSON key "retry_after_seconds".
 */
public record RecommendResponse(
        @JsonProperty("job_id") String jobId,
        String status,
        List<Place> places,
        @JsonProperty("visit_order") List<Integer> visitOrder,
        List<Leg> legs,
        String error,
        @JsonProperty("retry_after_seconds") Integer retryAfterSeconds
) {
}

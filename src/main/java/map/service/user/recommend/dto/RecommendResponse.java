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
 * clothing: agent llm_reason 노드가 생성한 날씨 기반 옷차림 안내(≤300자).
 *           degrade 시 null 일 수 있다.
 * error: 오류 메시지(있을 때).
 * retryAfterSeconds: 재시도 권장 대기 시간. JSON key "retry_after_seconds".
 * warnings: 추천 과정에서 반영하지 못한 조건 안내(예: 날씨 미확인).
 *           사용자에게 그대로 보여줄 문장 목록이며, 없으면 null.
 * timelineStatus: 방문 시각 계산 상태. "ok" | "trimmed" | "unverified".
 *                 타임라인 이전에 만들어진 draft 에는 없다. JSON key "timeline_status".
 */
public record RecommendResponse(
        @JsonProperty("job_id") String jobId,
        String status,
        List<Place> places,
        @JsonProperty("visit_order") List<Integer> visitOrder,
        List<Leg> legs,
        String clothing,
        String error,
        @JsonProperty("retry_after_seconds") Integer retryAfterSeconds,
        List<String> warnings,
        @JsonProperty("timeline_status") String timelineStatus
) {
}

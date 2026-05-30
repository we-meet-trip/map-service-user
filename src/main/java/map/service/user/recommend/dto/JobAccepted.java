package map.service.user.recommend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JobAccepted — 추천 작업 접수 응답
 *
 * agent 의 /v1/recommend 호출에 대해 즉시 받는 비동기 접수 확인 형식.
 * RecommendController.create / .research 의 202 Accepted 본문으로 그대로 내려간다.
 *
 * jobId: 발급된 작업 식별자. JSON key "job_id".
 * status: 접수 상태 문자열.
 * retryAfterSeconds: 결과 폴링 권장 대기 시간(초). JSON key "retry_after_seconds".
 */
public record JobAccepted(
        @JsonProperty("job_id") String jobId,
        String status,
        @JsonProperty("retry_after_seconds") int retryAfterSeconds
) {
}

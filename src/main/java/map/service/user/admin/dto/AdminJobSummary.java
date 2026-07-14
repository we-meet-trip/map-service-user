package map.service.user.admin.dto;

import java.time.OffsetDateTime;

/**
 * AdminJobSummary — 추천 작업 목록 행
 *
 * result_payload(대용량 JSON)는 목록에 싣지 않고 상태/오류/시각만 노출한다.
 *
 * jobId       : 작업 UUID.
 * scheduleId  : 연관 일정 식별자(nullable).
 * status      : in_progress/done/failed.
 * error       : 실패 사유(nullable).
 * createdAt   : 생성 시각.
 * finishedAt  : 완료 시각(nullable).
 */
public record AdminJobSummary(
        String jobId,
        String scheduleId,
        String status,
        String error,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt
) {
}

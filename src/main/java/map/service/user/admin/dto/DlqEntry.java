package map.service.user.admin.dto;

/**
 * DlqEntry — DLQ 스트림(agent:jobs:done:dlq) 항목
 *
 * RecommendJobsConsumer.routeToDlq 가 적재한 필드를 그대로 노출한다.
 *
 * recordId       : Redis Stream 레코드 ID(재처리/폐기 대상 지정에 사용).
 * jobId          : 작업 UUID.
 * status         : 원 메시지 상태(done/failed 등).
 * deliveryCount  : 재배달 횟수.
 * error          : DLQ 적재 사유.
 * payloadPreview : payload 앞부분 미리보기(길면 절단).
 */
public record DlqEntry(
        String recordId,
        String jobId,
        String status,
        String deliveryCount,
        String error,
        String payloadPreview
) {
}

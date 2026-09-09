package map.service.user.admin.dto;

/**
 * DlqEntry — DLQ 스트림(agent:jobs:done:dlq) 항목
 *
 * 재처리 대상과 고정 진단 코드만 노출하며 payload 원문은 반환하지 않는다.
 *
 * recordId       : Redis Stream 레코드 ID(재처리/폐기 대상 지정에 사용).
 * jobId          : 작업 UUID.
 * status         : 원 메시지 상태(done/failed 등).
 * deliveryCount  : 재배달 횟수.
 * error          : 허용된 DLQ 진단 코드(원문 오류 제외).
 * payloadPreview : 기존 클라이언트 호환용 비공개 표시 또는 null.
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

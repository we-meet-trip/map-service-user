package map.service.user.admin.dto;

import java.util.List;

/**
 * DlqActionResult — DLQ 재처리/폐기 결과 요약
 *
 * requested  : 요청된 recordId 개수.
 * succeeded  : 성공(재처리 후 XDEL / 폐기 XDEL)한 개수.
 * failed     : 실패한 개수.
 * failedIds  : 실패한 recordId 목록(존재하지 않거나 처리 중 오류).
 */
public record DlqActionResult(
        int requested,
        int succeeded,
        int failed,
        List<String> failedIds
) {
}

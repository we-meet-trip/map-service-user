package map.service.user.admin.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * DlqReprocessRequest — DLQ 재처리/폐기 요청 본문
 *
 * ids : 대상 Redis Stream 레코드 ID 목록(비어 있으면 400).
 */
public record DlqReprocessRequest(
        @NotEmpty(message = "ids must not be empty") List<String> ids
) {
}

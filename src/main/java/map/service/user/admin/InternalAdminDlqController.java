package map.service.user.admin;

import jakarta.validation.Valid;
import map.service.user.admin.dto.DlqActionResult;
import map.service.user.admin.dto.DlqEntry;
import map.service.user.admin.dto.DlqReprocessRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * InternalAdminDlqController — 운영 콘솔 위임 DLQ 조회/재처리/폐기 API
 *
 * InternalAdminGuardFilter 뒤에서만 접근 가능.
 *
 *   GET  /internal/admin/dlq?limit=          — DLQ 최근 항목(최신순)
 *   POST /internal/admin/dlq/reprocess       — {ids:[...]} 재처리 후 XDEL
 *   POST /internal/admin/dlq/discard         — {ids:[...]} 재처리 없이 XDEL
 */
@RestController
@RequestMapping("/internal/admin/dlq")
public class InternalAdminDlqController {

    private final AdminDlqService service;

    public InternalAdminDlqController(AdminDlqService service) {
        this.service = service;
    }

    /** DLQ 항목 조회(최신순, limit 개). */
    @GetMapping
    public List<DlqEntry> list(@RequestParam(defaultValue = "50") int limit) {
        return service.list(limit);
    }

    /** DLQ 항목 재처리(draft 저장 + 작업 완료 기록 후 XDEL). */
    @PostMapping("/reprocess")
    public DlqActionResult reprocess(@Valid @RequestBody DlqReprocessRequest body) {
        return service.reprocess(body.ids());
    }

    /** DLQ 항목 폐기(XDEL). */
    @PostMapping("/discard")
    public DlqActionResult discard(@Valid @RequestBody DlqReprocessRequest body) {
        return service.discard(body.ids());
    }
}

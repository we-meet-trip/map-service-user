package map.service.user.moderation;

import java.util.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Requires InternalAdminGuardFilter CIDR + token. Control plane authenticates the
 * operator and authorizes environment-scoped moderation before proxying requests. */
@RestController @RequestMapping("/internal/admin/moderation/reports")
public class InternalModerationController {
    private final ModerationService service;
    public InternalModerationController(ModerationService service) { this.service=service; }
    @GetMapping
    public List<ModerationService.Receipt> queue(@RequestParam(defaultValue="OPEN") ModerationReport.Status status,
            @RequestParam(defaultValue="50") int limit) { return service.queue(status,limit); }
    @GetMapping("/{id}")
    public ResponseEntity<ModerationService.AdminDetail> detail(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(id));
    }
    @PostMapping("/{id}/actions")
    public ModerationService.Receipt act(@PathVariable UUID id, @RequestHeader("X-Admin-Actor") String actor,
            @RequestBody ModerationService.ActionRequest request) { return service.act(id,actor,request); }
}

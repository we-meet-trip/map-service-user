package map.service.user.moderation;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/moderation")
public class ModerationController {
    private final ModerationService service;
    public ModerationController(ModerationService service) { this.service=service; }
    @PostMapping("/reports")
    public ResponseEntity<ModerationService.Receipt> report(@AuthenticationPrincipal Long user,
            @Valid @RequestBody ReportRequest request) {
        var result=service.submit(user,request);
        return ResponseEntity.status(result.created()?201:200).body(result.receipt());
    }
    @GetMapping("/reports")
    public List<ModerationService.Receipt> reports(@AuthenticationPrincipal Long user) { return service.ownReports(user); }
    @GetMapping("/blocks")
    public List<ModerationService.BlockReceipt> blocks(@AuthenticationPrincipal Long user) { return service.ownBlocks(user); }
    @PutMapping("/blocks/{target}")
    public ResponseEntity<Void> block(@AuthenticationPrincipal Long user, @PathVariable Long target) {
        service.block(user,target); return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/blocks/{target}")
    public ResponseEntity<Void> unblock(@AuthenticationPrincipal Long user, @PathVariable Long target) {
        service.unblock(user,target); return ResponseEntity.noContent().build();
    }
}

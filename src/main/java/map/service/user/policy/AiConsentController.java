package map.service.user.policy;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/consents/ai")
public class AiConsentController {
    @ExceptionHandler({org.springframework.dao.DataAccessException.class, org.springframework.transaction.TransactionException.class})
    public org.springframework.http.ResponseEntity<map.service.user.global.exception.ErrorResponse> unavailable() {
        var error = map.service.user.global.exception.ErrorCode.AI_CONSENT_UNAVAILABLE;
        return org.springframework.http.ResponseEntity.status(error.getHttpStatus())
                .body(map.service.user.global.exception.ErrorResponse.of(error));
    }
    private final AiConsentService service;
    public AiConsentController(AiConsentService service) { this.service = service; }
    @GetMapping public List<AiConsentService.Status> status(@AuthenticationPrincipal Long userId) {
        return service.status(userId);
    }
    @PostMapping("/{scope}") public AiConsentService.Status grant(@AuthenticationPrincipal Long userId,
            @PathVariable String scope, @Valid @RequestBody AiConsentService.Grant request) {
        return service.grant(userId, scope, request);
    }
    @DeleteMapping("/{scope}") public AiConsentService.Status revoke(@AuthenticationPrincipal Long userId,
            @PathVariable String scope, @RequestParam("expected_revision") long revision) {
        return service.revoke(userId, scope, revision);
    }
}

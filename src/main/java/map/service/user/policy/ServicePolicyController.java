package map.service.user.policy;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/consents")
public class ServicePolicyController {
    private final ServicePolicyService service;
    public ServicePolicyController(ServicePolicyService service) { this.service = service; }
    @GetMapping
    public ServicePolicyService.Status status(@AuthenticationPrincipal Long userId) {
        return service.status(userId);
    }
    @PostMapping
    public ServicePolicyService.Status accept(@AuthenticationPrincipal Long userId,
            @Valid @RequestBody ServicePolicyService.AcceptRequest request) {
        return service.accept(userId, request);
    }
}

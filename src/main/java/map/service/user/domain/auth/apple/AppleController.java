package map.service.user.domain.auth.apple;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import map.service.user.domain.auth.dto.response.AuthResponse;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;

@RestController
@RequestMapping("/api/v1/auth/apple")
@RequiredArgsConstructor
public class AppleController {
    private final AppleAccountService accounts;
    public record Login(@NotBlank @Size(max=16384) String identityToken,
                        @NotBlank @Size(max=4096) String authorizationCode,
                        @NotBlank @Size(max=128) String state,
                        @Size(max=50) String nickname) {}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> status(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("code","APPLE_AUTH_FAILED","message",error.getReason()==null ? "Apple authentication failed" : error.getReason()));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String,String>> conflict() {
        return ResponseEntity.status(409).body(Map.of("code","APPLE_LOGIN_CONFLICT","message","Apple 로그인을 다시 시도해주세요."));
    }
    @GetMapping("/nonce") public Map<String,String> nonce() { return accounts.challenge(); }
    @PostMapping("/callback") public AuthResponse callback(@Valid @RequestBody Login body) { return accounts.login(body); }
}

package map.service.user.domain.auth.apple;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Component
public record AppleSettings(
        @Value("${APPLE_ENABLED:false}") boolean enabled,
        @Value("${APPLE_CLIENT_ID:}") String clientId,
        @Value("${APPLE_TEAM_ID:}") String teamId,
        @Value("${APPLE_KEY_ID:}") String keyId,
        @Value("${APPLE_PRIVATE_KEY_B64:}") String privateKeyB64) {
    @Override public String toString() { return "AppleSettings[enabled="+enabled+", credentials=redacted]"; }
    public void requireConfigured() {
        if (!enabled || clientId.isBlank() || teamId.isBlank() || keyId.isBlank() || privateKeyB64.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Apple login is not configured");
        }
    }
}

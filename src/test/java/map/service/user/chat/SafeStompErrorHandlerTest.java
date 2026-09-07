package map.service.user.chat;

import map.service.user.chat.ws.SafeStompErrorHandler;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class SafeStompErrorHandlerTest {
    private final SafeStompErrorHandler handler = new SafeStompErrorHandler();
    @Test void wrappedChannelRejectionProducesMachineReadablePolicyCode() {
        for (ErrorCode code : new ErrorCode[]{ErrorCode.AGE_RESTRICTED, ErrorCode.SERVICE_POLICY_REQUIRED}) {
            var error = handler.handleClientMessageProcessingError(null,
                    new MessagingException("untrusted payload must not escape", new CustomException(code)));
            assertThat(StompHeaderAccessor.wrap(error).getCommand()).isEqualTo(StompCommand.ERROR);
            assertThat(new String(error.getPayload(), StandardCharsets.UTF_8)).contains("\"code\":\"" + code.getCode() + "\"")
                    .doesNotContain("untrusted payload");
        }
    }
    @Test void arbitraryErrorAndCredentialsAreNeverReflected() {
        var error = handler.handleClientMessageProcessingError(null,
                new IllegalArgumentException("Authorization Bearer synthetic-secret; private chat body"));
        assertThat(new String(error.getPayload(), StandardCharsets.UTF_8)).contains("STOMP_ERROR")
                .doesNotContain("Authorization", "synthetic-secret", "private chat");
        assertThat(StompHeaderAccessor.wrap(error).getMessage()).isEqualTo("STOMP_ERROR");
    }
}

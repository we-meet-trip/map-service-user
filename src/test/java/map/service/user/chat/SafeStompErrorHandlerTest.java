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
        // 구독 거부도 함께 돌려준다. 가리면 클라이언트가 사유를 몰라 계속 다시 붙고,
        // 방이 닫혀 있으면 그 시도도 같은 이유로 거절돼 재접속이 끝나지 않는다.
        for (ErrorCode code : new ErrorCode[]{ErrorCode.AGE_RESTRICTED, ErrorCode.SERVICE_POLICY_REQUIRED,
                ErrorCode.CHAT_ROOM_EXPIRED, ErrorCode.CHAT_NOT_PARTICIPANT}) {
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

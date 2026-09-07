package map.service.user.chat.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;
import java.util.Map;

/** Channel-interceptor failures never reach @MessageExceptionHandler. */
public class SafeStompErrorHandler extends StompSubProtocolErrorHandler {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable failure) {
        String code = "STOMP_ERROR";
        String message = "채팅 연결을 확인해주세요.";
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof CustomException custom &&
                    (custom.getErrorCode() == ErrorCode.AGE_RESTRICTED
                            || custom.getErrorCode() == ErrorCode.SERVICE_POLICY_REQUIRED)) {
                code = custom.getErrorCode().getCode();
                message = custom.getErrorCode().getMessage();
                break;
            }
        }
        StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.ERROR);
        headers.setMessage(code);
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            return MessageBuilder.createMessage(JSON.writeValueAsBytes(Map.of("code", code, "message", message)),
                    headers.getMessageHeaders());
        } catch (JsonProcessingException impossibleForStrings) {
            throw new IllegalStateException("stomp_error_serialization_failed");
        }
    }
}

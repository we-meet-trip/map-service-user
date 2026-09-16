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

    /**
     * 그대로 돌려줄 사유들. 나머지는 뭉뚱그린 문구로 가린다.
     *
     * 구독 거부를 함께 넣는 이유: 가려서 내보내면 클라이언트가 사유를 알 수 없어 그냥
     * 다시 붙는데, 방이 닫혀 있으면 그 시도도 같은 이유로 거절된다. 끝나지 않는 재접속이
     * 되므로, 다시 붙어 봐야 소용없는 거부는 그렇다고 말해 준다.
     */
    private static final java.util.Set<ErrorCode> SURFACED = java.util.EnumSet.of(
            ErrorCode.AGE_RESTRICTED, ErrorCode.SERVICE_POLICY_REQUIRED,
            ErrorCode.CHAT_ROOM_EXPIRED, ErrorCode.CHAT_NOT_PARTICIPANT);
    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable failure) {
        String code = "STOMP_ERROR";
        String message = "채팅 연결을 확인해주세요.";
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof CustomException custom && SURFACED.contains(custom.getErrorCode())) {
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

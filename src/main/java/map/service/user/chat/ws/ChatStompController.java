package map.service.user.chat.ws;

import java.security.Principal;
import lombok.extern.slf4j.Slf4j;
import map.service.user.chat.dto.ChatErrorEvent;
import map.service.user.chat.dto.MarkReadRequest;
import map.service.user.chat.dto.SendMessageRequest;
import map.service.user.chat.dto.TypingRequest;
import map.service.user.chat.service.ChatRealtimeService;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

/**
 * ChatStompController — STOMP 메시지 매핑 진입점
 *
 * 클라이언트가 /app 접두사로 보내는 프레임을 처리한다. 사용자 신원은 CONNECT 때 세션에
 * 설정된 Principal 에서 가져온다. 저장·브로드캐스트는 ChatRealtimeService 에 위임한다.
 *
 * 매핑:
 * - /app/rooms/{roomId}/send → 텍스트 메시지 전송
 *
 * 실패 통지: 전송·읽음·입력 처리 중 발생한 예외는 아래 예외 처리기가 받아 보낸 사람의
 * 개인 목적지(/user/queue/errors)로만 돌려준다. 이 처리기가 없으면 프레임워크가 예외를
 * 서버 로그에만 남기고 클라이언트에는 아무것도 보내지 않아, 보낸 사람 입장에서는 메시지가
 * 흔적 없이 사라진다(REST 로 같은 요청을 보내면 상태 코드와 사유가 온다).
 */
@Slf4j
@Controller
public class ChatStompController {

    private final ChatRealtimeService realtimeService;

    public ChatStompController(ChatRealtimeService realtimeService) {
        this.realtimeService = realtimeService;
    }

    @MessageMapping("/rooms/{roomId}/send")
    public void send(@DestinationVariable Long roomId,
                     @Payload SendMessageRequest request,
                     Principal principal) {
        realtimeService.sendMessage(roomId, userId(principal), request.content(), request.clientMsgId());
    }

    @MessageMapping("/rooms/{roomId}/read")
    public void read(@DestinationVariable Long roomId,
                     @Payload MarkReadRequest request,
                     Principal principal) {
        realtimeService.markRead(roomId, userId(principal), request.lastReadSeq());
    }

    @MessageMapping("/rooms/{roomId}/typing")
    public void typing(@DestinationVariable Long roomId,
                       @Payload TypingRequest request,
                       Principal principal) {
        realtimeService.broadcastTyping(roomId, userId(principal), request.typing());
    }

    /**
     * 계약된 실패 사유를 보낸 사람에게만 돌려준다.
     *
     * broadcast=false 라 이 세션에만 간다. 같은 사유를 REST 로 받았을 때와 코드가 같으므로
     * 클라이언트는 두 경로를 하나의 분기로 처리할 수 있다.
     */
    @MessageExceptionHandler(CustomException.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ChatErrorEvent handleCustom(CustomException e, Message<?> message) {
        ErrorCode code = e.getErrorCode();
        return new ChatErrorEvent(
                code.getCode(),
                code.getMessage(),
                code.getHttpStatus().value(),
                destinationOf(message));
    }

    /**
     * 계약에 없는 예외의 마지막 그물.
     *
     * 사유를 그대로 내보내면 내부 구현이 새므로 일반 문구로 접고, 원본은 서버 로그에 남긴다.
     * 여기서 잡지 않으면 프레임워크가 로그만 남기고 클라이언트는 계속 기다리게 된다.
     */
    @MessageExceptionHandler(Exception.class)
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ChatErrorEvent handleUnexpected(Exception e, Message<?> message) {
        String destination = destinationOf(message);
        log.error("chat stomp handler failed destination={} reason={}",
                destination, e.toString(), e);
        ErrorCode code = ErrorCode.INTERNAL_SERVER_ERROR;
        return new ChatErrorEvent(
                code.getCode(),
                code.getMessage(),
                code.getHttpStatus().value(),
                destination);
    }

    /** 실패한 요청의 목적지를 헤더에서 꺼낸다. 없으면 빈 문자열. */
    private String destinationOf(Message<?> message) {
        String destination = SimpMessageHeaderAccessor.getDestination(message.getHeaders());
        return destination == null ? "" : destination;
    }

    /** 세션 Principal 의 이름(userId 문자열)을 Long 으로 변환한다. */
    private Long userId(Principal principal) {
        return Long.parseLong(principal.getName());
    }
}

package map.service.user.chat.ws;

import java.security.Principal;
import map.service.user.chat.dto.MarkReadRequest;
import map.service.user.chat.dto.SendMessageRequest;
import map.service.user.chat.dto.TypingRequest;
import map.service.user.chat.service.ChatRealtimeService;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * ChatStompController — STOMP 메시지 매핑 진입점
 *
 * 클라이언트가 /app 접두사로 보내는 프레임을 처리한다. 사용자 신원은 CONNECT 때 세션에
 * 설정된 Principal 에서 가져온다. 저장·브로드캐스트는 ChatRealtimeService 에 위임한다.
 *
 * 매핑:
 * - /app/rooms/{roomId}/send → 텍스트 메시지 전송
 */
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
        realtimeService.sendMessage(roomId, userId(principal), request.content());
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

    /** 세션 Principal 의 이름(userId 문자열)을 Long 으로 변환한다. */
    private Long userId(Principal principal) {
        return Long.parseLong(principal.getName());
    }
}

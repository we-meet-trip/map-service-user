package map.service.user.chat.web;

import jakarta.validation.Valid;
import map.service.user.chat.dto.HistoryResponse;
import map.service.user.chat.dto.MarkReadRequest;
import map.service.user.chat.dto.MessageResponse;
import map.service.user.chat.dto.SendMessageRequest;
import map.service.user.chat.dto.UnreadResponse;
import map.service.user.chat.service.ChatMessageService;
import map.service.user.chat.service.ChatRealtimeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ChatMessageController — 메시지 히스토리/읽음/미읽음 HTTP 진입점
 *
 * /api/v1/chat 하위의 조회·읽음 엔드포인트를 노출하고 동작은 ChatMessageService 로
 * 위임한다. 모든 경로는 ACTIVE 참가자만 접근할 수 있다.
 *
 * 엔드포인트:
 * - GET  /rooms/{roomId}/messages?before_seq=&limit=  → 커서 히스토리
 * - POST /rooms/{roomId}/read                          → 읽음 처리(단조)
 * - GET  /rooms/{roomId}/unread                        → 미읽음 요약
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatMessageController {

    private final ChatMessageService messageService;
    private final ChatRealtimeService realtimeService;

    public ChatMessageController(ChatMessageService messageService,
                                 ChatRealtimeService realtimeService) {
        this.messageService = messageService;
        this.realtimeService = realtimeService;
    }

    /**
     * 메시지 전송 REST 폴백. WebSocket 전송과 같은 파사드를 타므로 저장·브로드캐스트 동작이
     * 동일하다. 신규 메시지를 만들었으므로 201 로 응답한다.
     */
    @PostMapping("/rooms/{roomId}/messages")
    public ResponseEntity<MessageResponse> send(
            @PathVariable Long roomId,
            @Valid @RequestBody SendMessageRequest request,
            @AuthenticationPrincipal Long userId
    ) {
        MessageResponse response = realtimeService.sendMessage(roomId, userId, request.content());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/rooms/{roomId}/messages")
    public HistoryResponse history(
            @PathVariable Long roomId,
            @RequestParam(name = "before_seq", required = false) Long beforeSeq,
            @RequestParam(name = "limit", required = false) Integer limit,
            @AuthenticationPrincipal Long userId
    ) {
        return messageService.getHistory(roomId, userId, beforeSeq, limit);
    }

    @PostMapping("/rooms/{roomId}/read")
    public UnreadResponse read(
            @PathVariable Long roomId,
            @Valid @RequestBody MarkReadRequest request,
            @AuthenticationPrincipal Long userId
    ) {
        // WebSocket 읽음 처리와 같은 파사드를 타 READ 이벤트도 함께 브로드캐스트한다.
        return realtimeService.markRead(roomId, userId, request.lastReadSeq());
    }

    @GetMapping("/rooms/{roomId}/unread")
    public UnreadResponse unread(@PathVariable Long roomId, @AuthenticationPrincipal Long userId) {
        return messageService.getUnread(roomId, userId);
    }
}

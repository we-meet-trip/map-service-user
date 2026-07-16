package map.service.user.chat.web;

import java.time.Duration;
import map.service.user.chat.dto.InvitePreview;
import map.service.user.chat.dto.InviteResponse;
import map.service.user.chat.dto.RoomResponse;
import map.service.user.chat.service.ChatInviteService;
import map.service.user.chat.service.ChatRealtimeService;
import map.service.user.global.config.ChatProperties;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.ratelimit.RateLimitService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ChatInviteController — 초대 링크 발급/폐기/미리보기/참가 HTTP 진입점
 *
 * /api/v1/chat 하위의 초대 관련 엔드포인트를 노출하고 동작은 ChatInviteService 로
 * 위임한다. 발급·폐기는 소유자만 가능하며, 미리보기·참가는 로그인한 사용자면 된다.
 *
 * 엔드포인트:
 * - POST   /rooms/{roomId}/invite → 발급/재발급(소유자)
 * - DELETE /rooms/{roomId}/invite → 폐기(소유자)
 * - GET    /invites/{token}       → 미리보기(참가 안 함)
 * - POST   /invites/{token}/join  → 참가
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatInviteController {

    private final ChatInviteService inviteService;
    private final ChatRealtimeService realtimeService;
    private final RateLimitService rateLimitService;
    private final ChatProperties chatProperties;

    public ChatInviteController(ChatInviteService inviteService,
                                ChatRealtimeService realtimeService,
                                RateLimitService rateLimitService,
                                ChatProperties chatProperties) {
        this.inviteService = inviteService;
        this.realtimeService = realtimeService;
        this.rateLimitService = rateLimitService;
        this.chatProperties = chatProperties;
    }

    /**
     * 초대 링크 발급/재발급. 발급 전에 사용자별 발급 레이트리밋을 검사해 남용을 막는다.
     * 한도를 넘으면 RATE_LIMIT_EXCEEDED(429)로 거부한다(Redis 장애 시 허용=fail-open).
     */
    @PostMapping("/rooms/{roomId}/invite")
    public InviteResponse generate(@PathVariable Long roomId, @AuthenticationPrincipal Long userId) {
        boolean allowed = rateLimitService.isAllowed(
                "chat:invite:" + userId,
                chatProperties.getInviteRateLimit(),
                Duration.ofSeconds(chatProperties.getInviteRateWindowSeconds()));
        if (!allowed) {
            throw new CustomException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
        return inviteService.generateOrRotate(roomId, userId);
    }

    @DeleteMapping("/rooms/{roomId}/invite")
    public ResponseEntity<Void> revoke(@PathVariable Long roomId, @AuthenticationPrincipal Long userId) {
        inviteService.revoke(roomId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/invites/{token}")
    public InvitePreview preview(@PathVariable String token) {
        return inviteService.preview(token);
    }

    @PostMapping("/invites/{token}/join")
    public RoomResponse join(@PathVariable String token, @AuthenticationPrincipal Long userId) {
        RoomResponse room = inviteService.join(token, userId);
        // 참가 성공 시 입장 안내 시스템 메시지를 남긴다.
        realtimeService.emitJoin(room.roomId(), userId);
        return room;
    }
}

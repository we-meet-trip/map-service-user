package map.service.user.chat.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import map.service.user.chat.dto.InvitePreview;
import map.service.user.chat.dto.InviteResponse;
import map.service.user.chat.dto.RoomResponse;
import map.service.user.chat.service.ChatInviteService;
import map.service.user.chat.service.ChatRealtimeService;
import map.service.user.global.config.ChatProperties;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.ratelimit.ClientIpResolver;
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
 * - GET    /invites/{token}       → 미리보기(무인증, 참가 안 함)
 * - POST   /invites/{token}/join  → 참가
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatInviteController {

    private final ChatInviteService inviteService;
    private final ChatRealtimeService realtimeService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;
    private final ChatProperties chatProperties;

    public ChatInviteController(ChatInviteService inviteService,
                                ChatRealtimeService realtimeService,
                                RateLimitService rateLimitService,
                                ClientIpResolver clientIpResolver,
                                ChatProperties chatProperties) {
        this.inviteService = inviteService;
        this.realtimeService = realtimeService;
        this.rateLimitService = rateLimitService;
        this.clientIpResolver = clientIpResolver;
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

    /**
     * 초대 링크 미리보기. 로그인하지 않아도 호출할 수 있다.
     *
     * 인증을 요구하지 않으므로 사용자 단위로 셀 수가 없어 발신 주소로 한도를 센다.
     * 발급 한도와 키 공간을 나누어, 링크를 나눠 준 사람이 받은 사람들의 조회 때문에
     * 발급을 막히는 일이 없게 한다.
     *
     * 이 메서드는 principal 을 받지 않는다. 익명 요청에서는 principal 이 비어 있어,
     * 받아 두면 로그인한 사람과 아닌 사람의 처리가 갈리는 자리가 생긴다.
     */
    @GetMapping("/invites/{token}")
    public InvitePreview preview(@PathVariable String token, HttpServletRequest request) {
        boolean allowed = rateLimitService.isAllowed(
                "chat:invite-preview:" + clientIpResolver.resolve(request),
                chatProperties.getInvitePreviewRateLimit(),
                Duration.ofSeconds(chatProperties.getInvitePreviewRateWindowSeconds()));
        if (!allowed) {
            throw new CustomException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
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

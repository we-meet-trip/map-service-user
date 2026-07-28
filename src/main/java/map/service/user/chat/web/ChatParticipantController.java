package map.service.user.chat.web;

import java.util.List;
import map.service.user.chat.dto.ParticipantResponse;
import map.service.user.chat.service.ChatParticipantService;
import map.service.user.chat.service.ChatRealtimeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ChatParticipantController — 참가자 목록/나가기/강퇴 HTTP 진입점
 *
 * /api/v1/chat 하위의 참가자 관련 엔드포인트를 노출하고 동작은 ChatParticipantService
 * 로 위임한다. 나가기는 본인, 강퇴는 소유자만 수행한다.
 *
 * 엔드포인트:
 * - GET    /rooms/{roomId}/participants              → 참가자 목록
 * - DELETE /rooms/{roomId}/participants/me           → 나가기(본인)
 * - DELETE /rooms/{roomId}/participants/{targetId}   → 강퇴(소유자)
 *
 * 경로 매칭에서 고정 세그먼트 "me" 가 변수 {targetUserId} 보다 우선하므로, 나가기와
 * 강퇴가 충돌 없이 구분된다.
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatParticipantController {

    private final ChatParticipantService participantService;
    private final ChatRealtimeService realtimeService;

    public ChatParticipantController(ChatParticipantService participantService,
                                     ChatRealtimeService realtimeService) {
        this.participantService = participantService;
        this.realtimeService = realtimeService;
    }

    @GetMapping("/rooms/{roomId}/participants")
    public List<ParticipantResponse> listParticipants(
            @PathVariable Long roomId,
            @AuthenticationPrincipal Long userId
    ) {
        return participantService.listParticipants(roomId, userId);
    }

    @GetMapping("/rooms/{roomId}/presence")
    public List<Long> presence(@PathVariable Long roomId, @AuthenticationPrincipal Long userId) {
        return participantService.listOnline(roomId, userId);
    }

    @DeleteMapping("/rooms/{roomId}/participants/me")
    public ResponseEntity<Void> leave(@PathVariable Long roomId, @AuthenticationPrincipal Long userId) {
        boolean roomClosed = participantService.leave(roomId, userId);
        if (roomClosed) {
            // 소유자가 나가 방이 종료됐으면 구독자에게 알린다.
            realtimeService.broadcastRoomClosed(roomId);
        }
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/rooms/{roomId}/participants/{targetUserId}")
    public ResponseEntity<Void> kick(
            @PathVariable Long roomId,
            @PathVariable Long targetUserId,
            @AuthenticationPrincipal Long userId
    ) {
        participantService.kick(roomId, userId, targetUserId);
        // 강퇴 안내 시스템 메시지를 남긴다.
        realtimeService.emitKick(roomId, targetUserId);
        return ResponseEntity.noContent().build();
    }
}

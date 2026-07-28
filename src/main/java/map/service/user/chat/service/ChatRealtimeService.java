package map.service.user.chat.service;

import java.time.Duration;
import map.service.user.chat.dto.MessageResponse;
import map.service.user.chat.dto.UnreadResponse;
import map.service.user.chat.ws.ChatBroadcastRelay;
import map.service.user.chat.ws.ChatEventEnvelope;
import map.service.user.global.config.ChatProperties;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.ratelimit.RateLimitService;
import org.springframework.stereotype.Service;

/**
 * ChatRealtimeService — 영속화와 실시간 브로드캐스트를 잇는 파사드
 *
 * "저장 후 전달" 흐름을 한곳에 모아 WebSocket 전송과 REST 폴백이 같은 경로를 타게 한다.
 * 먼저 트랜잭션 경계를 가진 서비스로 메시지를 저장(커밋)한 뒤, 커밋된 결과만 릴레이로
 * 발행한다. 저장이 실패하면 발행도 일어나지 않아, 저장되지 않은 메시지가 브로드캐스트되는
 * 일을 막는다.
 */
@Service
public class ChatRealtimeService {

    private final ChatMessageService messageService;
    private final ChatSystemMessageService systemMessageService;
    private final ChatRoomAccessService access;
    private final ChatBroadcastRelay relay;
    private final RateLimitService rateLimitService;
    private final ChatProperties chatProperties;

    public ChatRealtimeService(ChatMessageService messageService,
                               ChatSystemMessageService systemMessageService,
                               ChatRoomAccessService access,
                               ChatBroadcastRelay relay,
                               RateLimitService rateLimitService,
                               ChatProperties chatProperties) {
        this.messageService = messageService;
        this.systemMessageService = systemMessageService;
        this.access = access;
        this.relay = relay;
        this.rateLimitService = rateLimitService;
        this.chatProperties = chatProperties;
    }

    /**
     * 텍스트 메시지를 저장하고, 저장된 메시지를 방 구독자에게 브로드캐스트한다.
     *
     * 저장 전에 사용자별 전송 레이트리밋을 검사해, 짧은 시간에 지나치게 많은 전송을 막는다.
     * 한도를 넘으면 RATE_LIMIT_EXCEEDED(429)로 거부한다(Redis 장애 시에는 허용=fail-open).
     */
    public MessageResponse sendMessage(Long roomId, Long userId, String content) {
        boolean allowed = rateLimitService.isAllowed(
                "chat:send:" + userId,
                chatProperties.getSendRateLimit(),
                Duration.ofSeconds(chatProperties.getSendRateWindowSeconds()));
        if (!allowed) {
            throw new CustomException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
        MessageResponse response = messageService.send(roomId, userId, content);
        relay.publish(ChatEventEnvelope.message(response));
        return response;
    }

    /** 읽음 위치를 갱신하고, 갱신된 읽음 위치를 방 구독자에게 브로드캐스트한다. */
    public UnreadResponse markRead(Long roomId, Long userId, long lastReadSeq) {
        UnreadResponse response = messageService.markRead(roomId, userId, lastReadSeq);
        relay.publish(ChatEventEnvelope.read(roomId, userId, response.lastReadSeq()));
        return response;
    }

    /**
     * 입력 상태를 방 구독자에게 브로드캐스트한다. 저장은 하지 않으나, 참가자가 아닌
     * 사용자가 남의 방에 입력 상태를 흘리지 못하도록 ACTIVE 참가 자격은 확인한다.
     */
    public void broadcastTyping(Long roomId, Long userId, boolean typing) {
        access.requireActiveParticipant(roomId, userId);
        relay.publish(ChatEventEnvelope.typing(roomId, userId, typing));
    }

    /** 방 개설 직후 "일정 보러가기" 카드 시스템 메시지를 남긴다. */
    public void emitItineraryCard(Long roomId, Long scheduleId) {
        systemMessageService.emitItineraryCard(roomId, scheduleId);
    }

    /** 새 참가자 입장 안내 시스템 메시지를 남긴다. */
    public void emitJoin(Long roomId, Long userId) {
        systemMessageService.emitJoin(roomId, userId);
    }

    /** 강퇴 안내 시스템 메시지를 남긴다. */
    public void emitKick(Long roomId, Long userId) {
        systemMessageService.emitKick(roomId, userId);
    }

    /** 방이 보관 전용으로 전환됐음을 방 구독자에게 알린다(저장하지 않는 이벤트). */
    public void broadcastRoomClosed(Long roomId) {
        relay.publish(ChatEventEnvelope.roomClosed(roomId));
    }
}

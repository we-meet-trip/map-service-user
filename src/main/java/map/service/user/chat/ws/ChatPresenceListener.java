package map.service.user.chat.ws;

import java.security.Principal;
import map.service.user.chat.service.ChatPresenceService;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

/**
 * ChatPresenceListener — WebSocket 세션 수명 이벤트 → 프레즌스 반영
 *
 * 방 토픽 구독 시점과 세션 종료 시점을 잡아 접속 상태를 갱신한다. 방 토픽이 아닌 구독은
 * 무시한다. 실제 상태 집계·브로드캐스트는 ChatPresenceService 가 담당한다.
 */
@Component
public class ChatPresenceListener {

    private static final String ROOM_TOPIC_PREFIX = "/topic/rooms/";

    private final ChatPresenceService presenceService;

    public ChatPresenceListener(ChatPresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Long roomId = parseRoomId(accessor.getDestination());
        Principal user = event.getUser();
        String sessionId = accessor.getSessionId();
        if (roomId == null || user == null || sessionId == null) {
            return;
        }
        presenceService.onSubscribe(sessionId, roomId, Long.parseLong(user.getName()));
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        presenceService.onDisconnect(event.getSessionId());
    }

    /** "/topic/rooms/{roomId}" 목적지에서 roomId 를 파싱한다. 방 토픽이 아니면 null. */
    private Long parseRoomId(String destination) {
        if (destination == null || !destination.startsWith(ROOM_TOPIC_PREFIX)) {
            return null;
        }
        String rest = destination.substring(ROOM_TOPIC_PREFIX.length());
        int slash = rest.indexOf('/');
        String idPart = (slash >= 0) ? rest.substring(0, slash) : rest;
        try {
            return Long.parseLong(idPart);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

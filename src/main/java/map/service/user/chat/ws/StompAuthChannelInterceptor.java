package map.service.user.chat.ws;

import io.jsonwebtoken.Claims;
import java.security.Principal;
import java.time.OffsetDateTime;
import map.service.user.chat.entity.ChatRoom;
import map.service.user.chat.service.ChatRoomAccessService;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.jwt.JwtService;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.util.AntPathMatcher;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * StompAuthChannelInterceptor — STOMP 인바운드 인증·인가 인터셉터
 *
 * WebSocket 핸드셰이크 자체는 열려 있고, 실제 사용자 인증은 여기서 프레임 단위로 한다.
 *
 * - CONNECT: native "Authorization: Bearer" 헤더의 JWT 를 검증해 사용자 신원을 세션에
 *   설정한다. 토큰이 없거나 유효하지 않으면 예외를 던져 연결을 거부한다. 이로써
 *   auth.enforced 값과 무관하게 소켓 연결에 로그인을 강제한다.
 * - SUBSCRIBE: 방 토픽(/topic/rooms/{roomId}) 구독 시, 세션 사용자가 그 방의 ACTIVE
 *   참가자이고 방이 만료/보관 상태가 아닌지 검증한다. 아니면 구독을 거부한다.
 *
 * 방 토픽이 아닌 목적지(예: 사용자 전용 큐)는 방 인가를 건너뛴다.
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROOM_TOPIC_PREFIX = "/topic/rooms/";

    /** 브로커가 구독 매칭에 쓰는 것과 같은 대조기. 판정이 갈리지 않게 맞춘다. */
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final JwtService jwtService;
    private final ChatRoomAccessService access;
    private final map.service.user.moderation.ChatModerationGuard moderation;
    private final map.service.user.policy.ServicePolicyService policy;
    private final java.util.Map<String, String> sessionTokens = new java.util.concurrent.ConcurrentHashMap<>();

    public StompAuthChannelInterceptor(JwtService jwtService, ChatRoomAccessService access,
            map.service.user.moderation.ChatModerationGuard moderation,
            map.service.user.policy.ServicePolicyService policy) {
        this.jwtService = jwtService;
        this.access = access;
        this.moderation = moderation;
        this.policy = policy;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command)) {
            authenticateConnect(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(command) || StompCommand.SEND.equals(command)) {
            validateSession(accessor.getSessionId());
            if (StompCommand.SUBSCRIBE.equals(command)) authorizeSubscribe(accessor);
            else if (accessor.getDestination() == null || !accessor.getDestination().startsWith("/app/"))
                throw new MessagingException("unsupported destination");
        } else if (StompCommand.DISCONNECT.equals(command) && accessor.getSessionId() != null) {
            sessionTokens.remove(accessor.getSessionId());
        }
        return message;
    }

    /** CONNECT 프레임의 Bearer 토큰을 검증하고 사용자 신원을 세션 Principal 로 설정한다. */
    private void authenticateConnect(StompHeaderAccessor accessor) {
        String token = extractBearer(accessor.getFirstNativeHeader("Authorization"));
        if (token == null) {
            throw new MessagingException("missing authorization token");
        }
        Long userId;
        try {
            Claims claims = jwtService.validateAccessToken(token);
            userId = jwtService.extractUserId(claims);
        } catch (CustomException e) {
            throw new MessagingException("invalid authorization token");
        }
        policy.requireEligible(userId);
        accessor.setUser(new StompPrincipal(userId.toString()));
        if (accessor.getSessionId() != null) sessionTokens.put(accessor.getSessionId(), token);
    }

    /**
     * 방 토픽 구독 시 세션 사용자의 참가 자격과 방 상태를 검증한다(기본 거부).
     *
     * 브로커(SimpleBroker)는 구독 목적지를 패턴으로 취급해 발행 목적지와 매칭하므로,
     * 와일드카드 구독('/topic/rooms/*', '/topic/**')은 모든 방 브로드캐스트를 도청할 수
     * 있다. 따라서 와일드카드 문자를 포함한 목적지는 전면 거부하고, 방 토픽 접두어로
     * 시작하지만 방 번호가 숫자가 아니면(빈/비숫자) 거부한다. 정확히 '/topic/rooms/{숫자}'
     * 만 ACTIVE 참가자에게 허용한다.
     */
    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }
        // 패턴 구독은 전면 거부한다. 브로커가 구독 목적지를 경로 패턴으로 보고
        // 실제 방 토픽에 맞춰 보기 때문에, 패턴 하나로 남의 방 대화를 함께 받는다.
        // 별표와 물음표만 막으면 '/topic/{a}/{b}' 같은 중괄호 형태가 남는다 —
        // 그 목적지는 방 토픽 접두어로 시작하지 않아 아래 인가 검사도 건너뛴다.
        // 판정을 브로커와 같은 대조기에 맡겨, 새 패턴 문법이 생겨도 함께 막히게 한다.
        if (PATH_MATCHER.isPattern(destination)) {
            throw new CustomException(ErrorCode.CHAT_NOT_PARTICIPANT);
        }
        if (!destination.startsWith(ROOM_TOPIC_PREFIX)) {
            if (destination.startsWith("/user/queue/")) return;
            throw new CustomException(ErrorCode.CHAT_NOT_PARTICIPANT);
        }
        Long roomId = parseRoomId(destination);
        if (roomId == null) {
            // 접두어로 시작하나 방 번호가 아니면 기본 거부(빈/비숫자 세그먼트).
            throw new CustomException(ErrorCode.CHAT_NOT_PARTICIPANT);
        }
        Long userId = requireUserId(accessor);
        ChatRoom room = access.requireRoom(roomId);
        access.requireActiveParticipant(roomId, userId);
        if (room.isReadOnly() || room.isExpired(OffsetDateTime.now())) {
            // 만료/보관 방은 새 실시간 구독을 받지 않는다(기록은 REST 로 열람).
            throw new CustomException(ErrorCode.CHAT_ROOM_EXPIRED);
        }
    }

    private Long validateSession(String sessionId) {
        String token = sessionId == null ? null : sessionTokens.get(sessionId);
        if (token == null) throw new MessagingException("unauthenticated session");
        Long userId = jwtService.extractUserId(jwtService.validateAccessToken(token));
        policy.requireEligible(userId);
        return userId;
    }

    /** Recheck membership and token for each delivery, including subscriptions opened before leaving. */
    public Message<?> authorizeOutbound(Message<?> message) {
        var headers = org.springframework.messaging.simp.SimpMessageHeaderAccessor.wrap(message);
        if (headers.getMessageType() != org.springframework.messaging.simp.SimpMessageType.MESSAGE) return message;
        try {
            Long uid = validateSession(headers.getSessionId());
            String destination = headers.getDestination();
            if (destination != null && destination.startsWith(ROOM_TOPIC_PREFIX)) {
                Long room = parseRoomId(destination);
                if (room == null) return null;
                access.requireActiveParticipant(room, uid);
                if (!moderation.mayDeliver(uid, room, message.getPayload())) return null;
            }
            return message;
        } catch (RuntimeException denied) {
            return null;
        }
    }

    @org.springframework.context.event.EventListener
    public void disconnected(org.springframework.web.socket.messaging.SessionDisconnectEvent event) {
        sessionTokens.remove(event.getSessionId());
    }

    /** "Bearer <token>" 에서 토큰만 떼어낸다. 형식이 아니면 null. */
    private String extractBearer(String header) {
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    /** "/topic/rooms/{roomId}" 목적지에서 roomId 를 파싱한다. 방 토픽이 아니면 null. */
    private Long parseRoomId(String destination) {
        if (destination == null || !destination.startsWith(ROOM_TOPIC_PREFIX)) {
            return null;
        }
        String idPart = destination.substring(ROOM_TOPIC_PREFIX.length());
        if (!idPart.matches("[0-9]+")) return null;
        try {
            return Long.parseLong(idPart);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 세션에 설정된 사용자 식별자를 반환한다. 없으면(미인증) 거부한다. */
    private Long requireUserId(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (user == null) {
            throw new MessagingException("unauthenticated subscribe");
        }
        return Long.parseLong(user.getName());
    }
}

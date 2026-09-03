package map.service.user.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Claims;
import java.time.OffsetDateTime;
import map.service.user.chat.entity.ChatParticipant;
import map.service.user.chat.entity.ChatRoom;
import map.service.user.chat.service.ChatRoomAccessService;
import map.service.user.chat.ws.StompAuthChannelInterceptor;
import map.service.user.chat.ws.StompPrincipal;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.jwt.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;

/**
 * ChatStompAuthInterceptorTest — STOMP 인증·인가 인터셉터 단위 검증
 *
 * 12단계(자동화 부분): 실제 STOMP 프레임(CONNECT/SUBSCRIBE)을 만들어 인터셉터를 직접
 * 호출해, 토큰 검증과 방 구독 인가를 확인한다. Redis·소켓 없이 프레임 처리 로직만 격리
 * 검증하며, 전체 send→broadcast 경로는 compose 수동 런북에서 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("STOMP 인증·인가 인터셉터 테스트")
class ChatStompAuthInterceptorTest {

    @Mock private JwtService jwtService;
    @Mock private ChatRoomAccessService access;

    private StompAuthChannelInterceptor interceptor() {
        return new StompAuthChannelInterceptor(jwtService, access);
    }

    /** 주어진 command 로 가변(mutable) STOMP 메시지를 만든다(인터셉터가 헤더를 수정할 수 있게). */
    private Message<byte[]> frame(StompCommand command, String destination, String authHeader,
                                  StompPrincipal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (authHeader != null) {
            accessor.setNativeHeader("Authorization", authHeader);
        }
        if (user != null) {
            accessor.setUser(user);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private StompHeaderAccessor accessorOf(Message<?> message) {
        return MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    }

    // ── CONNECT ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CONNECT — 유효 토큰이면 세션 사용자(Principal) 설정")
    void connect_validToken_setsPrincipal() {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        when(jwtService.validateAccessToken("tok")).thenReturn(claims);
        when(jwtService.extractUserId(claims)).thenReturn(7L);

        Message<?> result = interceptor()
                .preSend(frame(StompCommand.CONNECT, null, "Bearer tok", null), null);

        assertThat(accessorOf(result).getUser()).isNotNull();
        assertThat(accessorOf(result).getUser().getName()).isEqualTo("7");
    }

    @Test
    @DisplayName("CONNECT — 토큰이 없으면 연결 거부")
    void connect_missingToken_rejected() {
        Message<byte[]> message = frame(StompCommand.CONNECT, null, null, null);

        assertThatThrownBy(() -> interceptor().preSend(message, null))
                .isInstanceOf(MessagingException.class);
    }

    @Test
    @DisplayName("CONNECT — 유효하지 않은 토큰이면 연결 거부")
    void connect_invalidToken_rejected() {
        when(jwtService.validateAccessToken("bad"))
                .thenThrow(new CustomException(ErrorCode.INVALID_TOKEN));
        Message<byte[]> message = frame(StompCommand.CONNECT, null, "Bearer bad", null);

        assertThatThrownBy(() -> interceptor().preSend(message, null))
                .isInstanceOf(MessagingException.class);
    }

    // ── SUBSCRIBE ───────────────────────────────────────────────────────────

    private ChatRoom room(OffsetDateTime expiresAt) {
        return new ChatRoom(1L, 1L, "방", expiresAt);
    }

    @Test
    @DisplayName("SUBSCRIBE — ACTIVE 참가자이고 방이 열려 있으면 허용")
    void subscribe_activeParticipant_allowed() {
        when(access.requireRoom(10L)).thenReturn(room(OffsetDateTime.now().plusDays(1)));
        when(access.requireActiveParticipant(10L, 7L))
                .thenReturn(new ChatParticipant(10L, 7L, ChatParticipant.Role.MEMBER));
        Message<byte[]> message =
                frame(StompCommand.SUBSCRIBE, "/topic/rooms/10", null, new StompPrincipal("7"));

        // 예외 없이 통과해야 한다.
        Message<?> result = interceptor().preSend(message, null);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("SUBSCRIBE — 참가자가 아닌 방 구독은 거부")
    void subscribe_foreignRoom_denied() {
        when(access.requireRoom(10L)).thenReturn(room(OffsetDateTime.now().plusDays(1)));
        when(access.requireActiveParticipant(10L, 7L))
                .thenThrow(new CustomException(ErrorCode.CHAT_NOT_PARTICIPANT));
        Message<byte[]> message =
                frame(StompCommand.SUBSCRIBE, "/topic/rooms/10", null, new StompPrincipal("7"));

        assertThatThrownBy(() -> interceptor().preSend(message, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_NOT_PARTICIPANT);
    }

    @Test
    @DisplayName("SUBSCRIBE — 만료된 방은 새 구독 거부")
    void subscribe_expiredRoom_denied() {
        when(access.requireRoom(10L)).thenReturn(room(OffsetDateTime.now().minusDays(1)));
        when(access.requireActiveParticipant(10L, 7L))
                .thenReturn(new ChatParticipant(10L, 7L, ChatParticipant.Role.MEMBER));
        Message<byte[]> message =
                frame(StompCommand.SUBSCRIBE, "/topic/rooms/10", null, new StompPrincipal("7"));

        assertThatThrownBy(() -> interceptor().preSend(message, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_EXPIRED);
    }

    @Test
    @DisplayName("SUBSCRIBE — 방 토픽이 아니면 인가 없이 통과")
    void subscribe_nonRoomTopic_skipped() {
        Message<byte[]> message =
                frame(StompCommand.SUBSCRIBE, "/user/queue/notify", null, new StompPrincipal("7"));

        Message<?> result = interceptor().preSend(message, null);
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("SUBSCRIBE — 패턴 목적지는 거부(전 방 도청 방지)")
    void subscribe_wildcardDestination_denied() {
        // 중괄호 형태는 별표도 물음표도 없어 예전 검사를 그냥 통과했고, 방 토픽
        // 접두어로 시작하지도 않아 참가자 확인마저 건너뛰었다. 그런데 브로커는
        // 그것을 경로 패턴으로 보고 실제 방 토픽에 맞춰 보내므로, 한 번 구독으로
        // 남의 방 대화가 함께 왔다.
        for (String dest : new String[] {
                "/topic/rooms/*", "/topic/**", "/topic/rooms/1*",
                "/topic/{a}/{b}", "/topic/{x}/5", "/topic/rooms/{id}"}) {
            Message<byte[]> message = frame(StompCommand.SUBSCRIBE, dest, null, new StompPrincipal("7"));
            assertThatThrownBy(() -> interceptor().preSend(message, null))
                    .as("wildcard destination %s must be denied", dest)
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.CHAT_NOT_PARTICIPANT);
        }
    }

    @Test
    @DisplayName("SUBSCRIBE — 방 접두어이나 방 번호가 숫자가 아니면 거부(기본 거부)")
    void subscribe_nonNumericRoom_denied() {
        Message<byte[]> message =
                frame(StompCommand.SUBSCRIBE, "/topic/rooms/abc", null, new StompPrincipal("7"));

        assertThatThrownBy(() -> interceptor().preSend(message, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_NOT_PARTICIPANT);
    }
}

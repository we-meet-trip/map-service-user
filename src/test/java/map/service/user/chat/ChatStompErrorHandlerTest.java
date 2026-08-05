package map.service.user.chat;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import map.service.user.chat.config.ChatWebSocketConfig;
import map.service.user.chat.dto.ChatErrorEvent;
import map.service.user.chat.service.ChatRealtimeService;
import map.service.user.chat.ws.ChatStompController;
import map.service.user.global.config.ChatProperties;
import map.service.user.global.config.CorsProperties;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.support.MessageBuilder;

/**
 * ChatStompErrorHandlerTest — 실시간 전송 실패 통지 검증
 *
 * WebSocket 으로 보낸 요청이 실패했을 때 그 사실이 보낸 사람에게 돌아가는지 확인한다.
 * 처리기가 없으면 프레임워크가 예외를 서버 로그에만 남기고 클라이언트에는 아무것도
 * 보내지 않아, 보낸 사람에게는 메시지가 흔적 없이 사라진 것으로 보인다. 그 상태로
 * 되돌아가지 않도록 통지 페이로드와 개인 목적지 설정을 함께 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("STOMP 실패 통지 테스트")
class ChatStompErrorHandlerTest {

    @Mock private ChatRealtimeService realtimeService;

    private ChatStompController controller() {
        return new ChatStompController(realtimeService);
    }

    /** 목적지 헤더를 가진 인바운드 메시지를 만든다. */
    private Message<byte[]> inbound(String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("계약된 실패는 같은 코드·상태로 통지된다")
    void customExceptionIsReportedWithSameCode() {
        ChatErrorEvent event = controller().handleCustom(
                new CustomException(ErrorCode.RATE_LIMIT_EXCEEDED),
                inbound("/app/rooms/7/send"));

        assertThat(event.code()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED.getCode());
        assertThat(event.message()).isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED.getMessage());
        assertThat(event.status())
                .isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED.getHttpStatus().value());
        assertThat(event.destination()).isEqualTo("/app/rooms/7/send");
        assertThat(event.type()).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("만료 방 전송 실패도 통지된다")
    void expiredRoomIsReported() {
        ChatErrorEvent event = controller().handleCustom(
                new CustomException(ErrorCode.CHAT_ROOM_EXPIRED),
                inbound("/app/rooms/7/send"));

        assertThat(event.code()).isEqualTo("CHAT_005");
        assertThat(event.status()).isEqualTo(410);
    }

    @Test
    @DisplayName("계약에 없는 예외는 내부 사유를 감추고 일반 문구로 통지된다")
    void unexpectedExceptionIsMasked() {
        ChatErrorEvent event = controller().handleUnexpected(
                new IllegalStateException("connection pool exhausted at jdbc:postgresql://db"),
                inbound("/app/rooms/7/read"));

        assertThat(event.code()).isEqualTo(ErrorCode.INTERNAL_SERVER_ERROR.getCode());
        assertThat(event.message()).doesNotContain("jdbc");
        assertThat(event.destination()).isEqualTo("/app/rooms/7/read");
    }

    @Test
    @DisplayName("목적지 헤더가 없어도 통지가 만들어진다")
    void missingDestinationDoesNotBreakReporting() {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        Message<byte[]> message =
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        ChatErrorEvent event = controller().handleCustom(
                new CustomException(ErrorCode.CHAT_MESSAGE_INVALID), message);

        assertThat(event.destination()).isEmpty();
        assertThat(event.code()).isEqualTo("CHAT_011");
    }

    @Test
    @DisplayName("통지는 보낸 사람 개인 목적지로만 나간다(방 전체로 새지 않는다)")
    void reportsGoToTheSenderOnly() throws Exception {
        for (String name : new String[]{"handleCustom", "handleUnexpected"}) {
            Method method = findHandler(name);
            assertThat(method.getAnnotation(MessageExceptionHandler.class))
                    .as("%s 에 예외 처리기 표시가 있어야 프레임워크가 호출한다", name)
                    .isNotNull();
            SendToUser sendToUser = method.getAnnotation(SendToUser.class);
            assertThat(sendToUser).as("%s 통지 목적지", name).isNotNull();
            assertThat(sendToUser.destinations()).containsExactly("/queue/errors");
            assertThat(sendToUser.broadcast())
                    .as("broadcast=true 면 같은 사용자의 다른 세션까지 남의 실패를 받는다")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("브로커가 개인 큐 접두어를 함께 다룬다")
    void brokerCoversPrivateQueuePrefix() {
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);
        new ChatWebSocketConfig(new ChatProperties(), new CorsProperties(), null)
                .configureMessageBroker(registry);

        // /queue 가 빠지면 사용자 전용 목적지가 브로커에 등록되지 않아 통지가
        // 구독도 전달도 되지 않은 채 조용히 사라진다.
        verify(registry).enableSimpleBroker("/topic", "/queue");
        verify(registry).setUserDestinationPrefix("/user");
        verify(registry).setApplicationDestinationPrefixes("/app");
    }

    private Method findHandler(String name) {
        for (Method m : ChatStompController.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("handler not found: " + name);
    }
}

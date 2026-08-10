package map.service.user.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import map.service.user.chat.dto.MessageResponse;
import map.service.user.chat.dto.UnreadResponse;
import map.service.user.chat.service.ChatMessageService;
import map.service.user.chat.service.ChatRealtimeService;
import map.service.user.chat.service.ChatRoomAccessService;
import map.service.user.chat.service.ChatSystemMessageService;
import map.service.user.chat.ws.ChatBroadcastRelay;
import map.service.user.chat.ws.ChatEventEnvelope;
import map.service.user.chat.ws.ReadEvent;
import map.service.user.chat.ws.TypingEvent;
import map.service.user.global.config.ChatProperties;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.ratelimit.RateLimitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ChatRealtimeServiceTest — 실시간 파사드 브로드캐스트 검증 (Mockito)
 *
 * 7단계 audit: 전송·읽음·타이핑이 각각 MESSAGE/READ/TYPING 봉투로 릴레이에 발행되는지,
 * 타이핑은 참가 자격 확인을 거치는지 확인한다. 저장 위임(메시지 서비스)은 목으로 대체하고
 * 발행 봉투의 타입·roomId·payload 를 캡처해 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Chat 실시간 파사드 테스트")
class ChatRealtimeServiceTest {

    @Mock private ChatMessageService messageService;
    @Mock private ChatSystemMessageService systemMessageService;
    @Mock private ChatRoomAccessService access;
    @Mock private ChatBroadcastRelay relay;
    @Mock private RateLimitService rateLimitService;

    private final ChatProperties chatProperties = new ChatProperties();

    private ChatRealtimeService realtimeService() {
        return new ChatRealtimeService(messageService, systemMessageService, access, relay,
                rateLimitService, chatProperties);
    }

    private ArgumentCaptor<ChatEventEnvelope> capturePublish() {
        return ArgumentCaptor.forClass(ChatEventEnvelope.class);
    }

    @Test
    @DisplayName("전송 — 저장 후 MESSAGE 봉투를 발행")
    void sendMessage_publishesMessageEnvelope() {
        when(rateLimitService.isAllowed(any(), anyInt(), any())).thenReturn(true);
        MessageResponse saved = new MessageResponse(10L, 1L, 1L, "TEXT", "hi", null,
                OffsetDateTime.now(), 2L, "c-1");
        when(messageService.send(10L, 1L, "hi", "c-1")).thenReturn(saved);

        realtimeService().sendMessage(10L, 1L, "hi", "c-1");

        ArgumentCaptor<ChatEventEnvelope> captor = capturePublish();
        verify(relay).publish(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("MESSAGE");
        assertThat(captor.getValue().roomId()).isEqualTo(10L);
        assertThat(captor.getValue().data()).isEqualTo(saved);
    }

    @Test
    @DisplayName("전송 — 레이트리밋 초과 시 429 거부, 저장·발행 없음")
    void sendMessage_rateLimited_rejects() {
        when(rateLimitService.isAllowed(any(), anyInt(), any())).thenReturn(false);

        assertThatThrownBy(() -> realtimeService().sendMessage(10L, 1L, "hi", "c-1"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);

        verify(messageService, never()).send(any(), any(), any(), any());
        verify(relay, never()).publish(any());
    }

    @Test
    @DisplayName("읽음 — 갱신 후 READ 봉투(userId, lastReadSeq)를 발행")
    void markRead_publishesReadEnvelope() {
        UnreadResponse result = new UnreadResponse(10L, 1L, 4L, 5L);
        when(messageService.markRead(10L, 2L, 4L)).thenReturn(result);

        realtimeService().markRead(10L, 2L, 4L);

        ArgumentCaptor<ChatEventEnvelope> captor = capturePublish();
        verify(relay).publish(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("READ");
        assertThat(captor.getValue().roomId()).isEqualTo(10L);
        ReadEvent event = (ReadEvent) captor.getValue().data();
        assertThat(event.userId()).isEqualTo(2L);
        assertThat(event.lastReadSeq()).isEqualTo(4L);
    }

    @Test
    @DisplayName("타이핑 — 참가 자격 확인 후 TYPING 봉투를 발행")
    void broadcastTyping_checksParticipantAndPublishes() {
        realtimeService().broadcastTyping(10L, 3L, true);

        verify(access).requireActiveParticipant(eq(10L), eq(3L));
        ArgumentCaptor<ChatEventEnvelope> captor = capturePublish();
        verify(relay).publish(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("TYPING");
        TypingEvent event = (TypingEvent) captor.getValue().data();
        assertThat(event.userId()).isEqualTo(3L);
        assertThat(event.typing()).isTrue();
    }

    @Test
    @DisplayName("타이핑 — 참가자가 아니면 발행하지 않음")
    void broadcastTyping_nonParticipant_doesNotPublish() {
        when(access.requireActiveParticipant(any(), any()))
                .thenThrow(new RuntimeException("not participant"));

        try {
            realtimeService().broadcastTyping(10L, 99L, true);
        } catch (RuntimeException ignored) {
            // 참가 자격 확인 실패로 예외가 발생하는 것이 정상이다.
        }

        verify(relay, org.mockito.Mockito.never()).publish(any());
    }
}

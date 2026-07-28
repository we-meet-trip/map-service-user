package map.service.user.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import map.service.user.chat.service.ChatPresenceService;
import map.service.user.chat.ws.ChatBroadcastRelay;
import map.service.user.chat.ws.ChatEventEnvelope;
import map.service.user.chat.ws.PresenceEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ChatPresenceServiceTest — 접속 상태 집계(인메모리) 검증 (Mockito)
 *
 * 첫 접속 세션에서만 online 을, 마지막 세션이 나갈 때만 offline 을 알리고, 중복 구독·다기기
 * 를 올바르게 처리하며, onlineUserIds 가 인메모리 카운터를 그대로 반영하는지 확인한다.
 * (Redis Set 을 제거해 유령 온라인이 남지 않도록 한 리팩터 이후 버전.)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Chat 프레즌스 서비스 테스트(인메모리)")
class ChatPresenceServiceTest {

    @Mock private ChatBroadcastRelay relay;

    private ChatPresenceService presenceService;

    @BeforeEach
    void setUp() {
        presenceService = new ChatPresenceService(relay);
    }

    private List<ChatEventEnvelope> publishes() {
        ArgumentCaptor<ChatEventEnvelope> captor = ArgumentCaptor.forClass(ChatEventEnvelope.class);
        verify(relay, org.mockito.Mockito.atLeast(0)).publish(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("첫 구독 — online 브로드캐스트 + onlineUserIds 반영")
    void firstSubscribe_online() {
        presenceService.onSubscribe("s1", 10L, 2L);

        List<ChatEventEnvelope> pubs = publishes();
        assertThat(pubs).hasSize(1);
        PresenceEvent event = (PresenceEvent) pubs.get(0).data();
        assertThat(event.userId()).isEqualTo(2L);
        assertThat(event.online()).isTrue();
        assertThat(presenceService.onlineUserIds(10L)).containsExactly(2L);
    }

    @Test
    @DisplayName("같은 세션 중복 구독 — online 재발행 없음")
    void duplicateSubscribe_noSecondOnline() {
        presenceService.onSubscribe("s1", 10L, 2L);
        presenceService.onSubscribe("s1", 10L, 2L);

        verify(relay, times(1)).publish(any());
    }

    @Test
    @DisplayName("다기기 — online 한 번, 마지막 세션 종료 시 offline 한 번 + onlineUserIds 비워짐")
    void multiSession_onlineOnceOfflineOnLast() {
        presenceService.onSubscribe("s1", 10L, 2L); // online
        presenceService.onSubscribe("s2", 10L, 2L); // 두 번째 세션 → 재발행 없음
        assertThat(presenceService.onlineUserIds(10L)).containsExactly(2L);

        presenceService.onDisconnect("s1"); // 아직 한 세션 → offline 없음
        assertThat(presenceService.onlineUserIds(10L)).containsExactly(2L);

        presenceService.onDisconnect("s2"); // 마지막 세션 → offline
        assertThat(presenceService.onlineUserIds(10L)).isEmpty();

        List<ChatEventEnvelope> pubs = publishes();
        assertThat(pubs).hasSize(2);
        assertThat(((PresenceEvent) pubs.get(0).data()).online()).isTrue();
        assertThat(((PresenceEvent) pubs.get(1).data()).online()).isFalse();
    }

    @Test
    @DisplayName("종료 시 카운터 정리 — 유령 온라인이 남지 않음")
    void disconnect_clearsGhost() {
        presenceService.onSubscribe("s1", 10L, 2L);
        presenceService.onSubscribe("s1", 10L, 3L); // 같은 세션, 다른 사용자? (방 동일)

        presenceService.onDisconnect("s1");

        assertThat(presenceService.onlineUserIds(10L)).isEmpty();
    }
}

package map.service.user.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import map.service.user.chat.entity.ChatRoom;
import map.service.user.chat.repository.ChatRoomRepository;
import map.service.user.chat.service.ChatExpirySweeper;
import map.service.user.chat.ws.ChatBroadcastRelay;
import map.service.user.chat.ws.ChatEventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * ChatExpirySweeperTest — 만료 sweep 통합 테스트 (H2)
 *
 * 9단계 audit: 만료됐으나 열려 있는 방만 read_only 로 전환하고 ROOM_CLOSED 를 알리며,
 * 미만료 방은 건드리지 않고, 이미 보관 전용인 방은 다시 처리하지 않음을 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Chat 만료 sweep 테스트 (H2)")
class ChatExpirySweeperTest {

    @Autowired private ChatRoomRepository roomRepository;
    @Autowired private TestEntityManager entityManager;

    private final ChatBroadcastRelay relay = Mockito.mock(ChatBroadcastRelay.class);
    private ChatExpirySweeper sweeper;
    private long scheduleSeq = 6000L;

    @BeforeEach
    void setUp() {
        sweeper = new ChatExpirySweeper(roomRepository, relay);
    }

    private ChatRoom saveRoom(OffsetDateTime expiresAt, boolean closed) {
        ChatRoom room = new ChatRoom(scheduleSeq++, 1L, "방", expiresAt);
        if (closed) {
            room.close();
        }
        return roomRepository.save(room);
    }

    @Test
    @DisplayName("만료된 열린 방만 read_only 전환 + ROOM_CLOSED 브로드캐스트")
    void sweep_closesOnlyExpiredOpenRooms() {
        ChatRoom expiredOpen = saveRoom(OffsetDateTime.now().minusDays(1), false);
        ChatRoom notExpired = saveRoom(OffsetDateTime.now().plusDays(1), false);
        ChatRoom alreadyClosed = saveRoom(OffsetDateTime.now().minusDays(1), true);
        entityManager.flush();
        entityManager.clear();

        sweeper.sweep();
        entityManager.flush();
        entityManager.clear();

        assertThat(roomRepository.findById(expiredOpen.getRoomId()).orElseThrow().isReadOnly()).isTrue();
        assertThat(roomRepository.findById(notExpired.getRoomId()).orElseThrow().isReadOnly()).isFalse();
        assertThat(roomRepository.findById(alreadyClosed.getRoomId()).orElseThrow().isReadOnly()).isTrue();

        ArgumentCaptor<ChatEventEnvelope> captor = ArgumentCaptor.forClass(ChatEventEnvelope.class);
        verify(captor);
        List<Long> closedRoomIds = captor.getAllValues().stream()
                .filter(e -> "ROOM_CLOSED".equals(e.type()))
                .map(ChatEventEnvelope::roomId)
                .toList();
        assertThat(closedRoomIds).containsExactly(expiredOpen.getRoomId());
    }

    private void verify(ArgumentCaptor<ChatEventEnvelope> captor) {
        Mockito.verify(relay, Mockito.atLeast(0)).publish(captor.capture());
    }
}

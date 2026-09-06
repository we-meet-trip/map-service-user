package map.service.user.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import map.service.user.chat.entity.ChatMessage;
import map.service.user.chat.entity.ChatRoom;
import map.service.user.chat.repository.ChatMessageRepository;
import map.service.user.chat.repository.ChatParticipantRepository;
import map.service.user.chat.repository.ChatRoomRepository;
import map.service.user.chat.service.ChatRoomAccessService;
import map.service.user.chat.service.ChatSystemMessageService;
import map.service.user.chat.ws.ChatBroadcastRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * ChatSystemMessageServiceTest — 시스템 메시지 저장 검증 (H2)
 *
 * 10단계 audit: 입장·일정 카드 시스템 메시지가 룸별 seq 로 SYSTEM 타입(sender 없음)으로
 * 저장되고, 구조화 payload(kind + 식별자)가 담기는지 검증한다. 커밋 이후 브로드캐스트는
 * 종단 간 테스트에서 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Chat 시스템 메시지 서비스 테스트 (H2)")
class ChatSystemMessageServiceTest {

    @Autowired private ChatRoomRepository roomRepository;
    @Autowired private ChatParticipantRepository participantRepository;
    @Autowired private ChatMessageRepository messageRepository;
    @Autowired private TestEntityManager entityManager;

    private ChatSystemMessageService systemMessageService;
    private long roomId;

    @BeforeEach
    void setUp() {
        ChatRoomAccessService access = new ChatRoomAccessService(roomRepository, participantRepository, org.mockito.Mockito.mock(map.service.user.chat.repository.ChatMembershipIntervalRepository.class));
        systemMessageService = new ChatSystemMessageService(
                messageRepository, access, Mockito.mock(ChatBroadcastRelay.class), new ObjectMapper());
        ChatRoom room = roomRepository.save(
                new ChatRoom(5555L, 1L, "방", OffsetDateTime.now().plusDays(8)));
        roomId = room.getRoomId();
    }

    @Test
    @DisplayName("입장 — SYSTEM 메시지(sender 없음) + kind=JOIN payload 저장")
    void emitJoin_persistsSystemMessage() {
        systemMessageService.emitJoin(roomId, 5L);
        entityManager.flush();
        entityManager.clear();

        ChatMessage message = messageRepository.findTopByRoomIdOrderBySeqDesc(roomId).orElseThrow();
        assertThat(message.getType()).isEqualTo(ChatMessage.MessageType.SYSTEM);
        assertThat(message.getSenderId()).isNull();
        assertThat(message.getSeq()).isEqualTo(1);
        assertThat(message.getSystemPayload().get("kind").asText()).isEqualTo("JOIN");
        assertThat(message.getSystemPayload().get("user_id").asLong()).isEqualTo(5L);
    }

    @Test
    @DisplayName("일정 카드 — kind=VIEW_ITINERARY + schedule_id payload 저장")
    void emitItineraryCard_persistsCard() {
        systemMessageService.emitItineraryCard(roomId, 99L);
        entityManager.flush();
        entityManager.clear();

        ChatMessage message = messageRepository.findTopByRoomIdOrderBySeqDesc(roomId).orElseThrow();
        assertThat(message.getSystemPayload().get("kind").asText()).isEqualTo("VIEW_ITINERARY");
        assertThat(message.getSystemPayload().get("schedule_id").asLong()).isEqualTo(99L);
    }
}

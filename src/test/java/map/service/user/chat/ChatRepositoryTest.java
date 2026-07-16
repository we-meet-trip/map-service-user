package map.service.user.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import map.service.user.chat.entity.ChatMessage;
import map.service.user.chat.entity.ChatParticipant;
import map.service.user.chat.entity.ChatRoom;
import map.service.user.chat.repository.ChatMessageRepository;
import map.service.user.chat.repository.ChatParticipantRepository;
import map.service.user.chat.repository.ChatRoomRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * ChatRepositoryTest — chat_rooms/chat_participants/chat_messages 통합 테스트 (H2)
 *
 * 1단계 audit: 엔티티↔DDL 부팅(H2 create-drop 스키마 생성), 유니크 제약 3종
 * (uq_chat_rooms_schedule, uq_chat_participant, uq_chat_message_seq), seq 커서 페이징,
 * 상태별 조회/개수, 읽음 포인터 단조 갱신을 검증한다.
 *
 * chat_* 엔티티는 user_service 스키마에 매핑되므로 @DataJpaTest 의 기본 임베디드 DB
 * 치환을 끄고(application-test.yaml 의 H2 URL + INIT 스키마 생성) 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Chat 리포지토리 통합 테스트 (H2)")
class ChatRepositoryTest {

    @Autowired
    private ChatRoomRepository roomRepository;

    @Autowired
    private ChatParticipantRepository participantRepository;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private TestEntityManager entityManager;

    private ChatRoom persistRoom(long scheduleId, long ownerId) {
        ChatRoom room = new ChatRoom(scheduleId, ownerId, "속초 당일치기",
                OffsetDateTime.now().plusDays(8));
        return roomRepository.save(room);
    }

    @Test
    @DisplayName("방·참가자·메시지 라운드트립 + seq 발급 증가")
    void roundTripAndSeqAllocation() {
        ChatRoom room = persistRoom(101L, 7L);
        assertThat(room.getRoomId()).isNotNull();
        assertThat(room.getCreatedAt()).isNotNull();

        participantRepository.save(new ChatParticipant(room.getRoomId(), 7L, ChatParticipant.Role.OWNER));

        long s1 = room.allocateNextSeq();
        long s2 = room.allocateNextSeq();
        assertThat(s1).isEqualTo(1L);
        assertThat(s2).isEqualTo(2L);
        messageRepository.save(ChatMessage.text(room.getRoomId(), s1, 7L, "속초 드디어 가는군요!!!"));
        messageRepository.save(ChatMessage.text(room.getRoomId(), s2, 7L, "바다가 진짜 예쁠 거예요"));
        entityManager.flush();
        entityManager.clear();

        assertThat(roomRepository.findByScheduleId(101L)).isPresent();
        assertThat(messageRepository.findTopByRoomIdOrderBySeqDesc(room.getRoomId()))
                .get().extracting(ChatMessage::getSeq).isEqualTo(2L);
    }

    @Test
    @DisplayName("uq_chat_rooms_schedule — 같은 schedule_id 로 두 방 생성 시 위반")
    void scheduleUniqueViolation() {
        persistRoom(202L, 7L);
        entityManager.flush();

        assertThatThrownBy(() -> {
            roomRepository.save(new ChatRoom(202L, 9L, "중복", OffsetDateTime.now().plusDays(8)));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("uq_chat_participant — 같은 (room,user) 참가행 중복 시 위반")
    void participantUniqueViolation() {
        ChatRoom room = persistRoom(303L, 7L);
        participantRepository.save(new ChatParticipant(room.getRoomId(), 7L, ChatParticipant.Role.OWNER));
        entityManager.flush();

        assertThatThrownBy(() -> {
            participantRepository.save(new ChatParticipant(room.getRoomId(), 7L, ChatParticipant.Role.MEMBER));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("uq_chat_message_seq — 같은 (room,seq) 메시지 중복 시 위반")
    void messageSeqUniqueViolation() {
        ChatRoom room = persistRoom(404L, 7L);
        messageRepository.save(ChatMessage.text(room.getRoomId(), 1L, 7L, "첫 메시지"));
        entityManager.flush();

        assertThatThrownBy(() -> {
            messageRepository.save(ChatMessage.text(room.getRoomId(), 1L, 8L, "중복 seq"));
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("seq 내림차순 커서 페이징 — 첫 페이지와 before_seq 커서")
    void cursorPagination() {
        ChatRoom room = persistRoom(505L, 7L);
        for (long seq = 1; seq <= 5; seq++) {
            messageRepository.save(ChatMessage.text(room.getRoomId(), seq, 7L, "msg-" + seq));
        }
        entityManager.flush();
        entityManager.clear();

        List<ChatMessage> firstPage = messageRepository
                .findByRoomIdOrderBySeqDesc(room.getRoomId(), PageRequest.of(0, 2));
        assertThat(firstPage).extracting(ChatMessage::getSeq).containsExactly(5L, 4L);

        List<ChatMessage> nextPage = messageRepository
                .findByRoomIdAndSeqLessThanOrderBySeqDesc(room.getRoomId(), 4L, PageRequest.of(0, 2));
        assertThat(nextPage).extracting(ChatMessage::getSeq).containsExactly(3L, 2L);
    }

    @Test
    @DisplayName("상태별 조회/개수 + 읽음 포인터 목록/단조 갱신")
    void statusQueriesAndReadPointer() {
        ChatRoom room = persistRoom(606L, 7L);
        participantRepository.save(new ChatParticipant(room.getRoomId(), 7L, ChatParticipant.Role.OWNER));
        ChatParticipant member = new ChatParticipant(room.getRoomId(), 8L, ChatParticipant.Role.MEMBER);
        participantRepository.save(member);
        ChatParticipant left = new ChatParticipant(room.getRoomId(), 9L, ChatParticipant.Role.MEMBER);
        left.leave();
        participantRepository.save(left);
        entityManager.flush();

        assertThat(participantRepository.countByRoomIdAndStatus(room.getRoomId(), ChatParticipant.Status.ACTIVE))
                .isEqualTo(2L);
        assertThat(participantRepository.findByUserIdAndStatus(8L, ChatParticipant.Status.ACTIVE))
                .hasSize(1);
        assertThat(participantRepository.findByRoomIdAndStatus(room.getRoomId(), ChatParticipant.Status.LEFT))
                .extracting(ChatParticipant::getUserId).containsExactly(9L);

        // 읽음 포인터 단조 갱신: 5 로 전진 후, 역행(3) 은 무시.
        int advanced = participantRepository.advanceReadPointer(room.getRoomId(), 8L, 5L);
        int regressed = participantRepository.advanceReadPointer(room.getRoomId(), 8L, 3L);
        entityManager.flush();
        entityManager.clear();
        assertThat(advanced).isEqualTo(1);
        assertThat(regressed).isZero();

        ChatParticipant reloaded = participantRepository
                .findByRoomIdAndUserId(room.getRoomId(), 8L).orElseThrow();
        assertThat(reloaded.getLastReadMessageSeq()).isEqualTo(5L);

        List<Long> activePointers = participantRepository
                .findReadPointers(room.getRoomId(), ChatParticipant.Status.ACTIVE);
        assertThat(activePointers).containsExactlyInAnyOrder(0L, 5L);
    }

    @Test
    @DisplayName("findByIdForUpdate — 비관적 락 조회가 방을 반환")
    void findByIdForUpdateReturnsRoom() {
        ChatRoom room = persistRoom(707L, 7L);
        entityManager.flush();
        entityManager.clear();

        Optional<ChatRoom> locked = roomRepository.findByIdForUpdate(room.getRoomId());
        assertThat(locked).isPresent();
        assertThat(locked.get().getScheduleId()).isEqualTo(707L);
    }
}

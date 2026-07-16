package map.service.user.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import map.service.user.chat.dto.MessageResponse;
import map.service.user.chat.entity.ChatParticipant;
import map.service.user.chat.entity.ChatRoom;
import map.service.user.chat.repository.ChatMessageRepository;
import map.service.user.chat.repository.ChatParticipantRepository;
import map.service.user.chat.repository.ChatRoomRepository;
import map.service.user.chat.service.ChatMessageService;
import map.service.user.chat.service.ChatRoomAccessService;
import map.service.user.global.config.ChatProperties;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * ChatSendServiceTest — 메시지 전송(저장) 로직 통합 테스트 (H2)
 *
 * 6단계 audit(저장 측면): 룸별 seq 발급, 메시지 저장, 발신자 읽음 포인터 전진, 발신자를
 * 제외한 안 읽은 인원수 계산, 만료 방·비참가자 거부를 실제 리포지토리로 검증한다. 실시간
 * 브로드캐스트(릴레이/STOMP)는 별도의 종단 간 테스트에서 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Chat 전송 서비스 통합 테스트 (H2)")
class ChatSendServiceTest {

    private static final long OWNER = 1L;

    @Autowired private ChatRoomRepository roomRepository;
    @Autowired private ChatParticipantRepository participantRepository;
    @Autowired private ChatMessageRepository messageRepository;
    @Autowired private TestEntityManager entityManager;

    private final ChatProperties props = new ChatProperties();
    private ChatMessageService messageService;
    private long scheduleSeq = 7000L;

    @BeforeEach
    void setUp() {
        ChatRoomAccessService access = new ChatRoomAccessService(roomRepository, participantRepository);
        messageService = new ChatMessageService(messageRepository, participantRepository, props, access);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /** owner + member 들을 참가시킨 빈 방을 만들고 roomId 를 반환한다. */
    private long freshRoom(OffsetDateTime expiresAt, long... memberIds) {
        ChatRoom room = new ChatRoom(scheduleSeq++, OWNER, "방", expiresAt);
        roomRepository.save(room);
        participantRepository.save(new ChatParticipant(room.getRoomId(), OWNER, ChatParticipant.Role.OWNER));
        for (long memberId : memberIds) {
            participantRepository.save(new ChatParticipant(room.getRoomId(), memberId, ChatParticipant.Role.MEMBER));
        }
        return room.getRoomId();
    }

    @Test
    @DisplayName("전송 — seq 발급·저장·발신자 읽음 전진")
    void send_allocatesSeqAndAdvancesSenderPointer() {
        long roomId = freshRoom(OffsetDateTime.now().plusDays(8), 2L);

        MessageResponse first = messageService.send(roomId, OWNER, "속초 드디어 가는군요!!!");
        MessageResponse second = messageService.send(roomId, 2L, "바다가 진짜 예쁠 거예요");

        assertThat(first.seq()).isEqualTo(1);
        assertThat(second.seq()).isEqualTo(2);
        flushAndClear();
        assertThat(roomRepository.findById(roomId).orElseThrow().getNextSeq()).isEqualTo(2);
        assertThat(messageRepository.findTopByRoomIdOrderBySeqDesc(roomId).orElseThrow().getContent())
                .isEqualTo("바다가 진짜 예쁠 거예요");
        // 발신자(2번)는 자기 메시지 seq=2 까지 읽음 처리됨
        assertThat(participantRepository.findByRoomIdAndUserId(roomId, 2L).orElseThrow()
                .getLastReadMessageSeq()).isEqualTo(2);
    }

    @Test
    @DisplayName("전송 — 안 읽은 인원수는 발신자를 제외한 ACTIVE 참가자 수")
    void send_unreadExcludesSender() {
        long roomId = freshRoom(OffsetDateTime.now().plusDays(8), 2L, 3L); // owner+2+3 = 3명

        MessageResponse response = messageService.send(roomId, OWNER, "안녕하세요");

        assertThat(response.unreadCount()).isEqualTo(2); // 3명 - 발신자 1
    }

    @Test
    @DisplayName("전송 — 만료된 방은 CHAT_ROOM_EXPIRED")
    void send_expiredRoom() {
        long roomId = freshRoom(OffsetDateTime.now().minusDays(1));

        assertThatThrownBy(() -> messageService.send(roomId, OWNER, "x"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_EXPIRED);
    }

    @Test
    @DisplayName("전송 — 참가자가 아니면 CHAT_NOT_PARTICIPANT")
    void send_nonParticipant() {
        long roomId = freshRoom(OffsetDateTime.now().plusDays(8));

        assertThatThrownBy(() -> messageService.send(roomId, 777L, "x"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_NOT_PARTICIPANT);
    }

    @Test
    @DisplayName("전송 — 빈 내용(공백)은 CHAT_MESSAGE_INVALID")
    void send_blankContent() {
        long roomId = freshRoom(OffsetDateTime.now().plusDays(8));

        assertThatThrownBy(() -> messageService.send(roomId, OWNER, "   "))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MESSAGE_INVALID);
    }

    @Test
    @DisplayName("전송 — 최대 길이 초과는 CHAT_MESSAGE_INVALID")
    void send_tooLong() {
        long roomId = freshRoom(OffsetDateTime.now().plusDays(8));
        String tooLong = "a".repeat(props.getMaxMessageLength() + 1);

        assertThatThrownBy(() -> messageService.send(roomId, OWNER, tooLong))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_MESSAGE_INVALID);
    }
}

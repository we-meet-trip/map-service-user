package map.service.user.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import map.service.user.chat.dto.HistoryResponse;
import map.service.user.chat.dto.MessageResponse;
import map.service.user.chat.dto.UnreadResponse;
import map.service.user.chat.entity.ChatMessage;
import map.service.user.chat.entity.ChatParticipant;
import map.service.user.chat.entity.ChatRoom;
import map.service.user.chat.repository.ChatMessageRepository;
import map.service.user.chat.repository.ChatParticipantRepository;
import map.service.user.chat.repository.ChatRoomRepository;
import map.service.user.chat.service.ChatMessageService;
import map.service.user.chat.service.ChatRoomAccessService;
import map.service.user.global.config.ChatProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * ChatMessageServiceTest — 히스토리·읽음·미읽음 서비스 로직 통합 테스트 (H2)
 *
 * 5단계 audit: seq 내림차순 커서 페이징, 메시지별 안 읽은 인원수(읽음 포인터 이분탐색),
 * 단조 읽음 처리(역행 무시)와 미읽음 요약을 실제 리포지토리로 검증한다. 각 markRead 는
 * 실서비스에서 요청마다 별도 트랜잭션이므로, 테스트에서는 flush/clear 로 그 경계를 흉내낸다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Chat 메시지/읽음 서비스 통합 테스트 (H2)")
class ChatMessageServiceTest {

    private static final long OWNER = 1L;

    @Autowired private map.service.user.chat.repository.ChatMembershipIntervalRepository intervals;
    @Autowired private ChatRoomRepository roomRepository;
    @Autowired private ChatParticipantRepository participantRepository;
    @Autowired private ChatMessageRepository messageRepository;
    @Autowired private TestEntityManager entityManager;

    private final ChatProperties props = new ChatProperties();
    private ChatMessageService messageService;
    private long scheduleSeq = 9000L;

    @BeforeEach
    void setUp() {
        ChatRoomAccessService access = new ChatRoomAccessService(roomRepository, participantRepository, intervals);
        messageService = new ChatMessageService(messageRepository, participantRepository, props, access);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    /** 방을 만들고 owner + 지정 member 들을 참가시키고 count 개의 텍스트 메시지를 넣는다. */
    private long createRoomWithMessages(int count, long... memberIds) {
        ChatRoom room = new ChatRoom(scheduleSeq++, OWNER, "방", OffsetDateTime.now().plusDays(8));
        roomRepository.save(room);
        participantRepository.save(new ChatParticipant(room.getRoomId(), OWNER, ChatParticipant.Role.OWNER));
        intervals.save(new map.service.user.chat.entity.ChatMembershipInterval(room.getRoomId(), OWNER, 0));
        for (long memberId : memberIds) {
            participantRepository.save(new ChatParticipant(room.getRoomId(), memberId, ChatParticipant.Role.MEMBER));
            intervals.save(new map.service.user.chat.entity.ChatMembershipInterval(room.getRoomId(), memberId, 0));
        }
        for (int i = 0; i < count; i++) {
            long seq = room.allocateNextSeq();
            messageRepository.save(ChatMessage.text(room.getRoomId(), seq, OWNER, "msg-" + seq));
        }
        roomRepository.save(room);
        return room.getRoomId();
    }

    private void setPointer(long roomId, long userId, long seq) {
        ChatParticipant participant = participantRepository.findByRoomIdAndUserId(roomId, userId).orElseThrow();
        participant.advanceReadPointer(seq);
        participantRepository.save(participant);
    }

    private MessageResponse messageOfSeq(List<MessageResponse> items, long seq) {
        return items.stream().filter(m -> m.seq() == seq).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("히스토리 — seq 내림차순 + 메시지별 안 읽은 인원수")
    void history_withUnreadCounts() {
        long roomId = createRoomWithMessages(5, 2L, 3L);
        // 읽음 포인터: owner=5, m2=2, m3=0 → 정렬 [0,2,5]
        setPointer(roomId, OWNER, 5);
        setPointer(roomId, 2L, 2);
        flushAndClear();

        HistoryResponse page = messageService.getHistory(roomId, OWNER, null, 10);

        assertThat(page.messages()).extracting(MessageResponse::seq).containsExactly(5L, 4L, 3L, 2L, 1L);
        assertThat(page.nextCursor()).isNull();
        // seq5: 포인터<5 = {0,2} → 2 / seq3: {0,2} → 2 / seq2: {0} → 1 / seq1: {0} → 1
        assertThat(messageOfSeq(page.messages(), 5).unreadCount()).isEqualTo(2);
        assertThat(messageOfSeq(page.messages(), 3).unreadCount()).isEqualTo(2);
        assertThat(messageOfSeq(page.messages(), 2).unreadCount()).isEqualTo(1);
        assertThat(messageOfSeq(page.messages(), 1).unreadCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("히스토리 — 커서 페이징(before_seq)으로 더 과거 페이지 조회")
    void history_cursorPaging() {
        long roomId = createRoomWithMessages(5, 2L);
        flushAndClear();

        HistoryResponse first = messageService.getHistory(roomId, OWNER, null, 2);
        assertThat(first.messages()).extracting(MessageResponse::seq).containsExactly(5L, 4L);
        assertThat(first.nextCursor()).isEqualTo(4L);

        HistoryResponse second = messageService.getHistory(roomId, OWNER, first.nextCursor(), 2);
        assertThat(second.messages()).extracting(MessageResponse::seq).containsExactly(3L, 2L);
        assertThat(second.nextCursor()).isEqualTo(2L);

        HistoryResponse last = messageService.getHistory(roomId, OWNER, second.nextCursor(), 2);
        assertThat(last.messages()).extracting(MessageResponse::seq).containsExactly(1L);
        assertThat(last.nextCursor()).isNull();
    }

    @Test
    @DisplayName("읽음 — 단조 전진 후 역행 요청은 무시, 미읽음 수 정확")
    void markRead_monotonic() {
        long roomId = createRoomWithMessages(5, 2L);
        flushAndClear();

        UnreadResponse advanced = messageService.markRead(roomId, 2L, 4);
        assertThat(advanced.lastReadSeq()).isEqualTo(4);
        assertThat(advanced.unreadCount()).isEqualTo(1); // 최신 5 - 읽음 4
        flushAndClear();
        assertThat(participantRepository.findByRoomIdAndUserId(roomId, 2L).orElseThrow()
                .getLastReadMessageSeq()).isEqualTo(4);

        // 더 낮은 순번 요청은 포인터를 되돌리지 않는다.
        UnreadResponse regress = messageService.markRead(roomId, 2L, 2);
        assertThat(regress.lastReadSeq()).isEqualTo(4);
        assertThat(regress.unreadCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("읽음 — 최신 순번을 넘는 요청은 최신으로 보정")
    void markRead_clampToLatest() {
        long roomId = createRoomWithMessages(3, 2L);
        flushAndClear();

        UnreadResponse response = messageService.markRead(roomId, 2L, 99);

        assertThat(response.lastReadSeq()).isEqualTo(3); // 최신 3 으로 보정
        assertThat(response.unreadCount()).isZero();
    }

    @Test
    @DisplayName("미읽음 요약 — 최신 순번에서 마지막 읽은 순번을 뺀 값")
    void getUnread_summary() {
        long roomId = createRoomWithMessages(5, 2L);
        setPointer(roomId, 2L, 2);
        flushAndClear();

        UnreadResponse summary = messageService.getUnread(roomId, 2L);

        assertThat(summary.latestSeq()).isEqualTo(5);
        assertThat(summary.lastReadSeq()).isEqualTo(2);
        assertThat(summary.unreadCount()).isEqualTo(3);
    }
}

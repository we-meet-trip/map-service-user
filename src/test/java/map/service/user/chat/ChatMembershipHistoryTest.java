package map.service.user.chat;

import static org.assertj.core.api.Assertions.*;
import java.time.OffsetDateTime;
import map.service.user.chat.entity.*;
import map.service.user.chat.repository.*;
import map.service.user.chat.service.*;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.config.ChatProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class ChatMembershipHistoryTest {
    @Autowired ChatRoomRepository rooms;
    @Autowired ChatParticipantRepository participants;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatMembershipIntervalRepository intervals;
    @Autowired UserRepository users;

    void send(ChatRoom room, int n) {
        for (int i=0; i<n; i++) messages.save(ChatMessage.text(room.getRoomId(), room.allocateNextSeq(), 1L, "message"));
    }
    @Test void leaveFreezesEveryReadPathAndRejoinShowsOnlyUnionOfParticipation() {
        ChatRoom room = rooms.save(new ChatRoom(99L, 1L, "room", OffsetDateTime.now().plusDays(1)));
        Long id=room.getRoomId();
        participants.save(new ChatParticipant(id, 1L, ChatParticipant.Role.OWNER));
        ChatParticipant member = participants.save(new ChatParticipant(id, 7L, ChatParticipant.Role.MEMBER));
        ChatRoomAccessService access = new ChatRoomAccessService(rooms, participants, intervals);
        ChatMessageService history = new ChatMessageService(messages, participants, new ChatProperties(), access);
        ChatParticipantService membership = new ChatParticipantService(participants, access,
                org.mockito.Mockito.mock(ChatPresenceService.class), users);
        send(room, 2); // before initial join
        access.openInterval(id, 7L, room.getNextSeq());
        send(room, 3); // authorized 3,4,5
        membership.leave(id, 7L);
        send(room, 2); // absent 6,7
        assertThat(history.getHistory(id, 7L, null, 20).messages()).extracting(m -> m.seq()).containsExactly(5L,4L,3L);
        assertThat(history.getUnread(id, 7L).latestSeq()).isEqualTo(5);
        assertThat(history.markRead(id, 7L, 999).lastReadSeq()).isEqualTo(5);
        assertThatThrownBy(() -> history.send(id, 7L, "blocked", null)).isInstanceOf(RuntimeException.class);
        member.reactivate();
        access.openInterval(id, 7L, room.getNextSeq());
        member.advanceReadPointer(room.getNextSeq());
        send(room, 2); // authorized 8,9
        assertThat(history.getHistory(id, 7L, null, 20).messages()).extracting(m -> m.seq())
                .containsExactly(9L,8L,5L,4L,3L);
        assertThat(history.getHistory(id, 7L, 8L, 2).messages()).extracting(m -> m.seq()).containsExactly(5L,4L);
        assertThat(history.getUnread(id, 7L).unreadCount()).isEqualTo(2);
        assertThatThrownBy(() -> history.getHistory(id, 99L, null, 20)).isInstanceOf(RuntimeException.class);
    }
}

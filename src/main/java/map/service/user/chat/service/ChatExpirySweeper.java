package map.service.user.chat.service;

import java.time.OffsetDateTime;
import java.util.List;
import map.service.user.chat.entity.ChatRoom;
import map.service.user.chat.repository.ChatRoomRepository;
import map.service.user.chat.ws.ChatBroadcastRelay;
import map.service.user.chat.ws.ChatEventEnvelope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ChatExpirySweeper — 만료된 방을 보관 전용으로 전환하는 주기 작업
 *
 * 만료 시각(date_end+유예일)을 지났으나 아직 열려 있는 방을 주기적으로 찾아 read_only 로
 * 전환하고, 방 구독자에게 종료(ROOM_CLOSED)를 알린다. read_only 플래그는 UX/알림용
 * 편의값이며, 전송 차단의 authoritative 판정은 전송 경로의 실시간 만료 검사가 담당한다
 * (따라서 sweep 이 아직 돌지 않았어도 만료된 방으로는 메시지를 보낼 수 없다).
 *
 * 실행 간격은 chat.expiry-sweep-interval-ms(기본 60초)로 설정한다.
 */
@Component
public class ChatExpirySweeper {

    private final ChatRoomRepository roomRepository;
    private final ChatBroadcastRelay relay;

    public ChatExpirySweeper(ChatRoomRepository roomRepository, ChatBroadcastRelay relay) {
        this.roomRepository = roomRepository;
        this.relay = relay;
    }

    @Scheduled(
            fixedDelayString = "${chat.expiry-sweep-interval-ms:60000}",
            initialDelayString = "${chat.expiry-sweep-interval-ms:60000}")
    @Transactional
    public void sweep() {
        List<ChatRoom> expired = roomRepository.findExpiredOpenRooms(OffsetDateTime.now());
        for (ChatRoom room : expired) {
            room.close();
            relay.publish(ChatEventEnvelope.roomClosed(room.getRoomId()));
        }
    }
}

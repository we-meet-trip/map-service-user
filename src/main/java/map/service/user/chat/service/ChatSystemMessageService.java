package map.service.user.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import map.service.user.chat.dto.MessageResponse;
import map.service.user.chat.entity.ChatMessage;
import map.service.user.chat.entity.ChatRoom;
import map.service.user.chat.repository.ChatMessageRepository;
import map.service.user.chat.ws.ChatBroadcastRelay;
import map.service.user.chat.ws.ChatEventEnvelope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ChatSystemMessageService — 시스템 메시지 발행
 *
 * 입장/강퇴 같은 안내 메시지와 "일정 보러가기" 같은 카드형 메시지를 방 타임라인에
 * 저장하고 방 구독자에게 브로드캐스트한다. 시스템 메시지는 보낸 사람이 없으며(senderId
 * null), 구조화 데이터는 systemPayload(JSONB)에 kind 와 함께 담아 클라이언트가 이름 등
 * 세부를 렌더링할 수 있게 한다(예: kind=JOIN, user_id=…).
 *
 * 저장은 트랜잭션 안에서, 브로드캐스트는 커밋 이후에 수행한다. 롤백되면 저장도 발행도
 * 일어나지 않아 존재하지 않는 시스템 메시지가 전달되는 일이 없다. 안 읽은 인원수는
 * 안내 성격상 표시하지 않으므로 0 으로 둔다.
 */
@Service
public class ChatSystemMessageService {

    private final ChatMessageRepository messageRepository;
    private final ChatRoomAccessService access;
    private final ChatBroadcastRelay relay;
    private final ObjectMapper objectMapper;

    public ChatSystemMessageService(ChatMessageRepository messageRepository,
                                    ChatRoomAccessService access,
                                    ChatBroadcastRelay relay,
                                    ObjectMapper objectMapper) {
        this.messageRepository = messageRepository;
        this.access = access;
        this.relay = relay;
        this.objectMapper = objectMapper;
    }

    /**
     * 입장 안내. 어느 사용자가 들어왔는지 payload 의 user_id 로 전달한다.
     *
     * 이 메서드가 트랜잭션 경계를 연다(외부에서 프록시로 호출되는 진입점). 내부 emit 은
     * 같은 빈이라 자기호출이지만, 여기서 이미 트랜잭션이 활성화되므로 락 조회가 정상 동작한다.
     */
    @Transactional
    public void emitJoin(Long roomId, Long userId) {
        emit(roomId, "새 멤버가 입장했습니다.", payload("JOIN", "user_id", userId));
    }

    /** 강퇴 안내. 어느 사용자가 내보내졌는지 payload 의 user_id 로 전달한다. */
    @Transactional
    public void emitKick(Long roomId, Long userId) {
        emit(roomId, "멤버가 채팅방에서 내보내졌습니다.", payload("KICK", "user_id", userId));
    }

    /** 방 개설 시 상단에 남기는 "일정 보러가기" 카드. schedule_id 로 일정에 연결한다. */
    @Transactional
    public void emitItineraryCard(Long roomId, Long scheduleId) {
        emit(roomId, "여행 일정을 확인해보세요.", payload("VIEW_ITINERARY", "schedule_id", scheduleId));
    }

    /**
     * 시스템 메시지를 저장하고 커밋 이후 브로드캐스트한다. 방 행을 잠근 채 룸별 seq 를
     * 발급해 저장하므로, 다른 전송과 seq 가 겹치지 않는다.
     *
     * 공개 emit* 진입점이 트랜잭션을 열고 이 메서드를 자기호출하므로, 여기 도달 시점에는
     * 항상 트랜잭션이 활성 상태다(@Transactional 은 자기호출에 적용되지 않으나 상위에서 이미 활성).
     */
    @Transactional
    public void emit(Long roomId, String content, JsonNode systemPayload) {
        ChatRoom room = access.requireRoomForUpdate(roomId);
        long seq = room.allocateNextSeq();
        ChatMessage message = messageRepository.save(ChatMessage.system(roomId, seq, content, systemPayload));
        // 시스템 메시지는 사용자가 보낸 것이 아니라 짝지을 임시 식별자가 없다.
        MessageResponse response = new MessageResponse(
                roomId, seq, null, "SYSTEM", content, systemPayload, message.getCreatedAt(), 0L, null);
        publishAfterCommit(ChatEventEnvelope.message(response));
    }

    /** kind 와 단일 식별자 필드를 담은 payload JSON 노드를 만든다. */
    private JsonNode payload(String kind, String key, Long value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("kind", kind);
        node.put(key, value);
        return node;
    }

    /**
     * 트랜잭션이 활성화되어 있으면 커밋 이후에, 아니면 즉시 브로드캐스트한다. 커밋 이후
     * 발행으로 저장되지 않은 메시지의 전달을 막는다.
     */
    private void publishAfterCommit(ChatEventEnvelope envelope) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    relay.publish(envelope);
                }
            });
        } else {
            relay.publish(envelope);
        }
    }
}

package map.service.user.chat.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import map.service.user.chat.dto.HistoryResponse;
import map.service.user.chat.dto.MessageResponse;
import map.service.user.chat.dto.UnreadResponse;
import map.service.user.chat.entity.ChatMessage;
import map.service.user.chat.entity.ChatParticipant;
import map.service.user.chat.entity.ChatRoom;
import map.service.user.chat.repository.ChatMessageRepository;
import map.service.user.chat.repository.ChatParticipantRepository;
import map.service.user.global.config.ChatProperties;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ChatMessageService — 메시지 히스토리·읽음 처리·미읽음 계산
 *
 * 히스토리는 seq 내림차순 커서 페이징으로 제공하며, 각 메시지에 대해 아직 읽지 않은
 * 참가자 수(안 읽은 인원수)를 함께 계산한다. 읽음 처리는 읽음 포인터를 단조 전진시키고,
 * 미읽음 요약은 호출자 기준으로 남은 안 읽은 수를 돌려준다.
 */
@Service
public class ChatMessageService {

    private final ChatMessageRepository messageRepository;
    private final ChatParticipantRepository participantRepository;
    private final ChatProperties chatProperties;
    private final ChatRoomAccessService access;

    public ChatMessageService(ChatMessageRepository messageRepository,
                              ChatParticipantRepository participantRepository,
                              ChatProperties chatProperties,
                              ChatRoomAccessService access) {
        this.messageRepository = messageRepository;
        this.participantRepository = participantRepository;
        this.chatProperties = chatProperties;
        this.access = access;
    }

    /**
     * 텍스트 메시지 전송(저장).
     *
     * 처리 순서:
     * 1) 방 행을 비관적 락으로 잠근다(seq 발급과 상태 검사를 직렬화).
     * 2) 호출자가 ACTIVE 참가자인지, 방이 전송 가능한 상태인지 검증한다.
     * 3) 룸별 다음 seq 를 발급해 메시지를 저장한다.
     * 4) 발신자의 읽음 포인터를 방금 보낸 seq 로 전진시킨다(자기 메시지는 읽음 처리).
     * 5) 이 메시지의 안 읽은 인원수는 발신자를 제외한 ACTIVE 참가자 수로 응답에 채운다
     *    (방금 보낸 메시지라 발신자 외에는 아직 아무도 읽지 않았다).
     *
     * 브로드캐스트는 트랜잭션 커밋 이후 상위 파사드가 수행한다.
     */
    @Transactional
    public MessageResponse send(Long roomId, Long userId, String content, String clientMsgId) {
        validateContent(content);
        ChatRoom room = access.requireRoomForUpdate(roomId);
        access.requireActiveParticipant(roomId, userId);
        access.assertSendable(room);

        long seq = room.allocateNextSeq();
        ChatMessage message = messageRepository.save(ChatMessage.text(roomId, seq, userId, content));
        participantRepository.advanceReadPointer(roomId, userId, seq);

        long unread = Math.max(0L, access.activeCount(roomId) - 1L);
        return toResponse(message, unread, clientMsgId);
    }

    /**
     * 메시지 히스토리 한 페이지.
     *
     * beforeSeq 가 null 이면 최신부터, 있으면 그 순번 미만을 seq 내림차순으로 조회한다.
     * limit 은 1..설정상한 으로 보정한다. 각 메시지의 안 읽은 인원수는, ACTIVE 참가자들의
     * 읽음 포인터를 한 번 로드해 오름차순 정렬한 뒤, 메시지 순번보다 작은 포인터 개수를
     * 이분탐색으로 세어 구한다(메시지마다 O(log n)). 페이지가 limit 보다 적으면 더 과거
     * 메시지가 없으므로 nextCursor 는 null 이다.
     */
    @Transactional(readOnly = true)
    public HistoryResponse getHistory(Long roomId, Long userId, Long beforeSeq, Integer limit) {
        access.requireRoom(roomId);
        access.requireReadableParticipant(roomId, userId);

        int pageSize = clampLimit(limit);
        PageRequest page = PageRequest.of(0, pageSize);
        List<ChatMessage> messages = messageRepository.findVisible(roomId, userId,
                beforeSeq == null ? Long.MAX_VALUE : beforeSeq, page);

        List<Long> pointers =
                participantRepository.findReadPointers(roomId, ChatParticipant.Status.ACTIVE);
        Collections.sort(pointers);

        List<MessageResponse> items = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            // 시스템 메시지는 안내 성격이라 안 읽은 인원수를 표시하지 않는다(발행 시와 동일하게 0).
            long unread = (message.getType() == ChatMessage.MessageType.SYSTEM)
                    ? 0L
                    : countPointersBelow(pointers, message.getSeq());
            items.add(toResponse(message, unread, null));
        }

        Long nextCursor = messages.size() < pageSize
                ? null
                : messages.get(messages.size() - 1).getSeq();
        return new HistoryResponse(items, nextCursor);
    }

    /**
     * 읽음 처리(단조 전진).
     *
     * lastReadSeq 를 0..최신순번 으로 보정한 뒤 읽음 포인터를 그 값으로 단조 전진시킨다
     * (현재보다 클 때만 갱신되므로 순서가 뒤바뀐 요청이 포인터를 되돌리지 않는다). 반환값의
     * 미읽음 수는 갱신 뒤 유효 읽음 위치를 기준으로 계산한다. 브로드캐스트는 상위 계층 담당.
     */
    @Transactional
    public UnreadResponse markRead(Long roomId, Long userId, long lastReadSeq) {
        ChatRoom room = access.requireRoom(roomId);
        ChatParticipant participant = access.requireReadableParticipant(roomId, userId);

        long latestSeq = latestVisible(roomId, userId);
        long target = Math.min(Math.max(lastReadSeq, 0L), latestSeq);
        participantRepository.advanceReadPointer(roomId, userId, target);

        long effectiveLastRead = Math.max(participant.getLastReadMessageSeq(), target);
        long unread = messageRepository.countVisibleAfter(roomId, userId, effectiveLastRead);
        return new UnreadResponse(roomId, unread, effectiveLastRead, latestSeq);
    }

    /** 호출자 기준 미읽음 요약. 방의 최신 순번에서 호출자의 마지막 읽은 순번을 뺀다(음수면 0). */
    @Transactional(readOnly = true)
    public UnreadResponse getUnread(Long roomId, Long userId) {
        ChatRoom room = access.requireRoom(roomId);
        ChatParticipant participant = access.requireReadableParticipant(roomId, userId);
        long latestSeq = latestVisible(roomId, userId);
        long lastRead = participant.getLastReadMessageSeq();
        long unread = messageRepository.countVisibleAfter(roomId, userId, lastRead);
        return new UnreadResponse(roomId, unread, lastRead, latestSeq);
    }

    private long latestVisible(Long roomId, Long userId) {
        return messageRepository.findVisible(roomId, userId, Long.MAX_VALUE, PageRequest.of(0, 1))
                .stream().findFirst().map(ChatMessage::getSeq).orElse(0L);
    }

    /**
     * 메시지 내용을 검증한다. 비어 있거나(널/공백) 설정 상한을 초과하면 CHAT_MESSAGE_INVALID.
     * WebSocket·REST 전송이 모두 send 를 거치므로 두 경로가 동일하게 검증된다.
     */
    private void validateContent(String content) {
        if (content == null || content.isBlank()
                || content.length() > chatProperties.getMaxMessageLength()) {
            throw new CustomException(ErrorCode.CHAT_MESSAGE_INVALID);
        }
    }

    /** limit 을 1..설정 상한(history-page-size) 으로 보정한다. null 이면 상한을 사용한다. */
    private int clampLimit(Integer limit) {
        int max = chatProperties.getHistoryPageSize();
        if (limit == null) {
            return max;
        }
        return Math.min(Math.max(limit, 1), max);
    }

    /**
     * 오름차순 정렬된 읽음 포인터에서 주어진 순번보다 작은 포인터 개수를 센다.
     * 이는 그 메시지를 아직 읽지 않은 참가자 수(안 읽은 인원수)와 같다. 하한(lower bound)을
     * 이분탐색으로 찾아 그 인덱스를 반환한다.
     */
    private long countPointersBelow(List<Long> sortedAscending, long seq) {
        int low = 0;
        int high = sortedAscending.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sortedAscending.get(mid) < seq) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * 메시지 엔티티를 응답 DTO 로 매핑한다. 안 읽은 인원수는 호출부에서 계산해 넣는다.
     *
     * clientMsgId 는 방금 보낸 요청을 되돌려 줄 때만 채운다. 저장하는 값이 아니라서
     * 지난 메시지를 다시 읽을 때는 넣을 것이 없다.
     */
    private MessageResponse toResponse(ChatMessage message, long unreadCount, String clientMsgId) {
        return new MessageResponse(
                message.getRoomId(),
                message.getSeq(),
                message.getSenderId(),
                message.getType().name(),
                message.getContent(),
                message.getSystemPayload(),
                message.getCreatedAt(),
                unreadCount,
                clientMsgId);
    }
}

package map.service.user.chat.service;

import java.time.OffsetDateTime;
import map.service.user.chat.dto.RoomResponse;
import map.service.user.chat.entity.ChatParticipant;
import map.service.user.chat.entity.ChatRoom;
import map.service.user.chat.repository.ChatParticipantRepository;
import map.service.user.chat.repository.ChatRoomRepository;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * ChatRoomAccessService — 방 접근 검증·인가·응답 구성 공용 서비스
 *
 * 여러 채팅 서비스가 공통으로 필요로 하는 "방 조회 + 권한/상태 검증 + 응답 매핑"을
 * 한곳에 모아 중복을 없앤다. 검증 실패는 모두 CustomException(ErrorCode) 로 던져
 * 전역 예외 처리에서 계약된 HTTP 상태로 변환된다.
 */
@Service
public class ChatRoomAccessService {

    private final ChatRoomRepository roomRepository;
    private final ChatParticipantRepository participantRepository;

    public ChatRoomAccessService(ChatRoomRepository roomRepository,
                                 ChatParticipantRepository participantRepository) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
    }

    /** roomId 로 방을 조회하고, 없으면 CHAT_ROOM_NOT_FOUND 를 던진다. */
    public ChatRoom requireRoom(Long roomId) {
        return roomRepository.findById(roomId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    /**
     * 방 행을 비관적 락으로 잠근 채 조회한다. 메시지 seq 발급과 정원 검사를 동일 락으로
     * 직렬화해야 하는 경로(전송·참가)에서 사용한다. 없으면 CHAT_ROOM_NOT_FOUND.
     */
    public ChatRoom requireRoomForUpdate(Long roomId) {
        return roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    /**
     * 호출자가 방의 ACTIVE 참가자인지 검증하고 그 참가행을 반환한다. 참가행이 없거나
     * 나가기/강퇴 상태이면 CHAT_NOT_PARTICIPANT.
     */
    public ChatParticipant requireActiveParticipant(Long roomId, Long userId) {
        ChatParticipant participant = participantRepository.findByRoomIdAndUserId(roomId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_NOT_PARTICIPANT));
        if (!participant.isActive()) {
            throw new CustomException(ErrorCode.CHAT_NOT_PARTICIPANT);
        }
        return participant;
    }

    /** 호출자가 방의 소유자인지 검증한다. ACTIVE 참가자이면서 역할이 OWNER 여야 한다. */
    public ChatParticipant requireOwner(Long roomId, Long userId) {
        ChatParticipant participant = requireActiveParticipant(roomId, userId);
        if (!participant.isOwner()) {
            throw new CustomException(ErrorCode.CHAT_NOT_OWNER);
        }
        return participant;
    }

    /** 방이 메시지를 받을 수 있는 상태인지 검증한다. 보관 전용이거나 만료 시각을 지났으면 거부. */
    public void assertSendable(ChatRoom room) {
        if (room.isReadOnly() || room.isExpired(OffsetDateTime.now())) {
            throw new CustomException(ErrorCode.CHAT_ROOM_EXPIRED);
        }
    }

    /**
     * 링크로 참가할 수 있는 상태인지 검증한다. 폐기된 링크는 CHAT_INVITE_REVOKED,
     * 보관 전용/만료 방은 CHAT_ROOM_EXPIRED 로 거부한다(정원 검사는 호출자 담당).
     */
    public void assertJoinable(ChatRoom room) {
        if (room.isInviteRevoked()) {
            throw new CustomException(ErrorCode.CHAT_INVITE_REVOKED);
        }
        if (room.isReadOnly() || room.isExpired(OffsetDateTime.now())) {
            throw new CustomException(ErrorCode.CHAT_ROOM_EXPIRED);
        }
    }

    /** 방의 현재 ACTIVE 참가자 수. 정원 검사·응답 구성에 쓰인다. */
    public int activeCount(Long roomId) {
        return (int) participantRepository.countByRoomIdAndStatus(roomId, ChatParticipant.Status.ACTIVE);
    }

    /** 방 엔티티를 응답 DTO 로 매핑한다. 참가자 수와 최신 seq 를 함께 계산해 채운다. */
    public RoomResponse toRoomResponse(ChatRoom room) {
        return new RoomResponse(
                room.getRoomId(),
                room.getScheduleId(),
                room.getOwnerId(),
                room.getTitle(),
                room.isReadOnly(),
                room.getExpiresAt(),
                activeCount(room.getRoomId()),
                room.getNextSeq()
        );
    }
}

package map.service.user.chat.service;

import java.time.OffsetDateTime;
import java.util.Optional;
import map.service.user.chat.dto.InvitePreview;
import map.service.user.chat.dto.InviteResponse;
import map.service.user.chat.dto.RoomResponse;
import map.service.user.chat.entity.ChatParticipant;
import map.service.user.chat.entity.ChatRoom;
import map.service.user.chat.repository.ChatParticipantRepository;
import map.service.user.chat.repository.ChatRoomRepository;
import map.service.user.global.config.ChatProperties;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ChatInviteService — 초대 링크 발급·폐기·참가 비즈니스 로직
 *
 * 소유자는 링크를 발급/재발급/폐기하고, 로그인한 사용자는 링크로 방에 참가한다.
 * 서버는 원시 토큰의 해시만 저장하며, 재발급/폐기 때마다 버전을 올려 이전 링크를
 * 무효화한다. 참가는 방 행을 잠근 채 정원을 확인해 동시 참가로 인한 초과를 막는다.
 */
@Service
public class ChatInviteService {

    private final ChatRoomRepository roomRepository;
    private final ChatParticipantRepository participantRepository;
    private final ChatInviteTokenFactory tokenFactory;
    private final ChatProperties chatProperties;
    private final ChatRoomAccessService access;

    public ChatInviteService(ChatRoomRepository roomRepository,
                             ChatParticipantRepository participantRepository,
                             ChatInviteTokenFactory tokenFactory,
                             ChatProperties chatProperties,
                             ChatRoomAccessService access) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.tokenFactory = tokenFactory;
        this.chatProperties = chatProperties;
        this.access = access;
    }

    /**
     * 초대 링크 발급 또는 재발급(소유자 전용).
     *
     * 새 원시 토큰을 만들어 방에 해시를 저장하고 버전을 올린다(이전 링크 무효화).
     * 만료/보관 방에는 발급하지 않는다. 응답의 원시 토큰은 여기서 한 번만 노출된다.
     */
    @Transactional
    public InviteResponse generateOrRotate(Long roomId, Long userId) {
        ChatRoom room = access.requireRoom(roomId);
        access.requireOwner(roomId, userId);
        access.assertSendable(room);

        String rawToken = tokenFactory.newRawToken();
        room.rotateInvite(tokenFactory.hash(rawToken));
        String url = chatProperties.getInviteBaseUrl() + rawToken;
        return new InviteResponse(rawToken, url, room.getInviteTokenVersion(), room.getExpiresAt());
    }

    /** 초대 링크 폐기(소유자 전용). 저장된 해시를 지우고 버전을 올려 배포된 링크를 무효화한다. */
    @Transactional
    public void revoke(Long roomId, Long userId) {
        ChatRoom room = access.requireRoom(roomId);
        access.requireOwner(roomId, userId);
        room.revokeInvite();
    }

    /**
     * 초대 링크 미리보기(참가하지 않음).
     *
     * 토큰 해시로 방을 찾고, 방 제목·현재 인원과 함께 지금 참가할 수 있는지(joinable)를
     * 계산해 돌려준다. 알 수 없는 토큰만 CHAT_INVITE_INVALID 로 거부하고, 폐기·만료·정원
     * 초과는 예외 대신 joinable=false 로 표현해 클라이언트가 방 정보를 보여줄 수 있게 한다.
     */
    @Transactional(readOnly = true)
    public InvitePreview preview(String rawToken) {
        ChatRoom room = roomRepository.findByInviteTokenHash(tokenFactory.hash(rawToken))
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_INVITE_INVALID));
        int count = access.activeCount(room.getRoomId());
        boolean joinable = !room.isInviteRevoked()
                && !room.isReadOnly()
                && !room.isExpired(OffsetDateTime.now())
                && count < chatProperties.getMaxParticipants();
        return new InvitePreview(room.getRoomId(), room.getTitle(), count, joinable, room.getExpiresAt());
    }

    /**
     * 초대 링크로 참가.
     *
     * 처리 순서:
     * 1) 토큰 해시로 방을 찾는다. 없으면 CHAT_INVITE_INVALID.
     * 2) 방 행을 잠그고(동시 참가 직렬화) 참가 가능 상태인지 검증한다(폐기·만료 거부).
     * 3) 이미 참가행이 있으면: ACTIVE 는 CHAT_ALREADY_PARTICIPANT, KICKED 는 CHAT_KICKED
     *    로 거부하고, LEFT 는 정원 여유가 있으면 재활성한다(재입장).
     * 4) 참가행이 없으면 정원을 확인해 여유가 있을 때만 MEMBER 로 등록한다. 없으면 CHAT_ROOM_FULL.
     *
     * 정원 검사와 등록을 같은 잠금 안에서 수행하므로 동시 참가로 상한을 넘지 않는다.
     */
    @Transactional
    public RoomResponse join(String rawToken, Long userId) {
        ChatRoom found = roomRepository.findByInviteTokenHash(tokenFactory.hash(rawToken))
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_INVITE_INVALID));
        ChatRoom room = access.requireRoomForUpdate(found.getRoomId());
        access.assertJoinable(room);

        Optional<ChatParticipant> existing =
                participantRepository.findByRoomIdAndUserId(room.getRoomId(), userId);
        if (existing.isPresent()) {
            ChatParticipant participant = existing.get();
            if (participant.isActive()) {
                throw new CustomException(ErrorCode.CHAT_ALREADY_PARTICIPANT);
            }
            if (participant.getStatus() == ChatParticipant.Status.KICKED) {
                throw new CustomException(ErrorCode.CHAT_KICKED);
            }
            requireCapacity(room.getRoomId());
            participant.reactivate();
            // 재입장 시점 이전 메시지는 안 읽은 인원수에 포함되지 않도록 읽음 위치를 현재까지 당긴다.
            participant.advanceReadPointer(room.getNextSeq());
            return access.toRoomResponse(room);
        }

        requireCapacity(room.getRoomId());
        ChatParticipant participant =
                new ChatParticipant(room.getRoomId(), userId, ChatParticipant.Role.MEMBER);
        // 입장 이전 메시지는 이 참가자의 미읽음으로 세지 않는다(카톡식: 입장 전 메시지는 대상 아님).
        participant.advanceReadPointer(room.getNextSeq());
        participantRepository.save(participant);
        return access.toRoomResponse(room);
    }

    /** 정원 여유를 확인한다. 이미 상한에 도달했으면 CHAT_ROOM_FULL 을 던진다. */
    private void requireCapacity(Long roomId) {
        if (access.activeCount(roomId) >= chatProperties.getMaxParticipants()) {
            throw new CustomException(ErrorCode.CHAT_ROOM_FULL);
        }
    }
}

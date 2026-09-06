package map.service.user.chat.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import map.service.user.chat.dto.ParticipantResponse;
import map.service.user.chat.entity.ChatParticipant;
import map.service.user.chat.entity.ChatRoom;
import map.service.user.chat.repository.ChatParticipantRepository;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ChatParticipantService — 참가자 목록·나가기·강퇴 비즈니스 로직
 *
 * 방의 참가자를 조회하고, 스스로 나가거나(소유자가 나가면 방 종료), 소유자가 특정
 * 참가자를 내보낸다. 상태를 바꿔 소프트 제거하므로 나간/내보내진 뒤에도 메시지 기록과
 * 참가 이력은 그대로 남는다.
 */
@Service
public class ChatParticipantService {

    private final ChatParticipantRepository participantRepository;
    private final ChatRoomAccessService access;
    private final ChatPresenceService presenceService;
    private final UserRepository userRepository;

    public ChatParticipantService(ChatParticipantRepository participantRepository,
                                  ChatRoomAccessService access,
                                  ChatPresenceService presenceService,
                                  UserRepository userRepository) {
        this.participantRepository = participantRepository;
        this.access = access;
        this.presenceService = presenceService;
        this.userRepository = userRepository;
    }

    /** 방의 현재 온라인 사용자 식별자 목록. 호출자는 ACTIVE 참가자여야 한다. */
    @Transactional(readOnly = true)
    public List<Long> listOnline(Long roomId, Long userId) {
        access.requireRoom(roomId);
        access.requireActiveParticipant(roomId, userId);
        return presenceService.onlineUserIds(roomId);
    }

    /**
     * 방의 ACTIVE 참가자 목록. 호출자는 ACTIVE 참가자여야 한다.
     *
     * 표시 이름을 채우기 위해 참가자들의 user_id 로 사용자 프로필을 한 번에 조회해
     * 닉네임을 매핑한다(프로필이 없으면 null).
     */
    @Transactional(readOnly = true)
    public List<ParticipantResponse> listParticipants(Long roomId, Long userId) {
        access.requireRoom(roomId);
        access.requireActiveParticipant(roomId, userId);
        List<ChatParticipant> participants =
                participantRepository.findByRoomIdAndStatus(roomId, ChatParticipant.Status.ACTIVE);
        Map<Long, String> nicknames = userRepository
                .findAllById(participants.stream().map(ChatParticipant::getUserId).toList())
                .stream()
                .collect(Collectors.toMap(User::getId, User::getNickname));
        return participants.stream()
                .map(participant -> new ParticipantResponse(
                        participant.getUserId(),
                        nicknames.get(participant.getUserId()),
                        participant.getRole().name(),
                        participant.getStatus().name(),
                        participant.getLastReadMessageSeq(),
                        participant.getJoinedAt()))
                .toList();
    }

    /**
     * 스스로 나가기.
     *
     * 호출자의 참가 상태를 LEFT 로 바꾼다. 호출자가 소유자이면 방을 보관 전용(read_only)
     * 으로 전환해 방을 종료한다(이후 전송 불가). 실시간 종료 통지는 실시간 계층이 담당한다.
     *
     * 반환값: 이번 나가기로 방이 종료(read_only 전환)됐는지 여부. 소유자가 나갔을 때만 true.
     * 컨트롤러는 이 값이 true 면 방 종료를 브로드캐스트한다.
     */
    @Transactional
    public boolean leave(Long roomId, Long userId) {
        ChatRoom room = access.requireRoomForUpdate(roomId);
        ChatParticipant participant = access.requireActiveParticipant(roomId, userId);
        access.closeInterval(roomId, userId, room.getNextSeq());
        participant.leave();
        if (participant.isOwner()) {
            room.close();
            return true;
        }
        return false;
    }

    /**
     * 강퇴(소유자 전용).
     *
     * 대상 참가자의 상태를 KICKED 로 바꾼다. 소유자는 강퇴 대상이 될 수 없으며(자기 자신
     * 포함), 대상이 없거나 소유자이면 CHAT_NOT_PARTICIPANT 로 거부한다. 소유자의 이탈은
     * 나가기 경로로만 처리된다.
     */
    @Transactional
    public void kick(Long roomId, Long ownerId, Long targetUserId) {
        ChatRoom room = access.requireRoomForUpdate(roomId);
        access.requireOwner(roomId, ownerId);
        ChatParticipant target = participantRepository.findByRoomIdAndUserId(roomId, targetUserId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_NOT_PARTICIPANT));
        if (target.isOwner()) {
            throw new CustomException(ErrorCode.CHAT_NOT_PARTICIPANT);
        }
        access.closeInterval(roomId, targetUserId, room.getNextSeq());
        target.kick();
    }
}

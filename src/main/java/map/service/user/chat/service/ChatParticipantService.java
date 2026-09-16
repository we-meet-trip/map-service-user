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
 * 방의 참가자를 조회하고, 스스로 나가거나(방장이 나가면 남은 사람에게 넘기고, 아무도
 * 없으면 방 종료), 방장이 특정 참가자를 내보낸다. 상태를 바꿔 소프트 제거하므로
 * 나간/내보내진 뒤에도 메시지 기록과 참가 이력은 그대로 남는다.
 */
@Service
public class ChatParticipantService {

    private final ChatParticipantRepository participantRepository;
    private final ChatRoomAccessService access;
    private final ChatPresenceService presenceService;
    private final UserRepository userRepository;
    private final map.service.user.moderation.ChatModerationGuard moderation;
    private final ChatSystemMessageService systemMessageService;

    public ChatParticipantService(ChatParticipantRepository participantRepository,
                                  ChatRoomAccessService access,
                                  ChatPresenceService presenceService,
                                  UserRepository userRepository, map.service.user.moderation.ChatModerationGuard moderation,
                                  ChatSystemMessageService systemMessageService) {
        this.participantRepository = participantRepository;
        this.access = access;
        this.presenceService = presenceService;
        this.userRepository = userRepository;
        this.moderation = moderation;
        this.systemMessageService = systemMessageService;
    }

    /** 방의 현재 온라인 사용자 식별자 목록. 호출자는 ACTIVE 참가자여야 한다. */
    @Transactional(readOnly = true)
    public List<Long> listOnline(Long roomId, Long userId) {
        access.requireRoom(roomId);
        access.requireActiveParticipant(roomId, userId);
        return presenceService.onlineUserIds(roomId).stream().filter(actor -> !moderation.blocked(userId, actor)).toList();
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
                        moderation.blocked(userId, participant.getUserId()) ? 0L : participant.getLastReadMessageSeq(),
                        participant.getJoinedAt()))
                .toList();
    }

    /**
     * 스스로 나가기.
     *
     * 호출자의 참가 상태를 LEFT 로 바꾼다. 호출자가 방장이면 남아 있는 참가자 중 가장 먼저
     * 들어온 사람에게 방장을 넘기고 방은 그대로 열어 둔다 — 한 사람이 떠난다고 나머지의
     * 대화까지 끝낼 이유는 없다. 넘길 사람이 아무도 없을 때만 방을 보관 전용으로 닫는다.
     *
     * 넘길 때는 떠나는 사람의 역할도 같은 트랜잭션에서 MEMBER 로 내린다. 상태만 바꾸고 두면
     * 회수되지 않은 초대 링크로 돌아왔을 때 방장이 둘이 되고, 서로를 내보낼 수 없다.
     *
     * 반환값: 이번 나가기로 방이 닫혔는지 여부. 호출자는 true 일 때만 종료를 알린다.
     */
    @Transactional
    public boolean leave(Long roomId, Long userId) {
        ChatRoom room = access.requireRoomForUpdate(roomId);
        ChatParticipant participant = access.requireActiveParticipant(roomId, userId);
        access.closeInterval(roomId, userId, room.getNextSeq());
        participant.leave();
        if (!participant.isOwner()) {
            return false;
        }
        // 위에서 LEFT 로 바꾼 것이 조회 전에 반영되므로 떠나는 사람은 결과에 없다.
        // ponytail: 승계 순서는 joined_at 이라 나갔다 돌아온 사람이 쭉 있던 사람을 앞설 수
        // 있다. 정확히 하려면 chat_membership_intervals.start_seq 를 봐야 한다.
        List<ChatParticipant> remaining = participantRepository
                .findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(roomId, ChatParticipant.Status.ACTIVE);
        if (remaining.isEmpty()) {
            room.close();
            return true;
        }
        ChatParticipant successor = remaining.get(0);
        successor.promoteToOwner();
        room.transferOwnership(successor.getUserId());
        participant.demoteToMember();
        // 탈퇴 경로는 컨트롤러를 거치지 않으므로 여기서 알린다. 컨트롤러에서 내면
        // 탈퇴로 넘어간 방만 조용해진다.
        systemMessageService.emitOwnerChanged(roomId, successor.getUserId());
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

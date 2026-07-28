package map.service.user.chat.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import map.service.user.chat.dto.RoomResponse;
import map.service.user.chat.dto.RoomSummary;
import map.service.user.chat.entity.ChatMessage;
import map.service.user.chat.entity.ChatParticipant;
import map.service.user.chat.entity.ChatRoom;
import map.service.user.chat.repository.ChatMessageRepository;
import map.service.user.chat.repository.ChatParticipantRepository;
import map.service.user.chat.repository.ChatRoomRepository;
import map.service.user.global.config.ChatProperties;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.schedule.ScheduleEntity;
import map.service.user.schedule.ScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ChatRoomService — 채팅방 생성·조회 비즈니스 로직
 *
 * 저장된 일정에 1:1 로 묶인 방을 만들고 조회한다. 방 개설은 일정 소유자만 할 수 있으며,
 * 같은 일정으로 방이 이미 있으면 새로 만들지 않고 기존 방을 돌려준다(생성 또는 조회).
 * 만료 시각은 방을 만드는 순간 일정 종료일에 유예일을 더한 KST 하루 끝으로 고정한다.
 */
@Service
public class ChatRoomService {

    /** 만료 시각 계산의 기준 시간대. 종료일의 '그날 끝'을 이 시간대로 해석한다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ChatRoomRepository roomRepository;
    private final ChatParticipantRepository participantRepository;
    private final ChatMessageRepository messageRepository;
    private final ScheduleRepository scheduleRepository;
    private final ChatProperties chatProperties;
    private final ChatRoomAccessService access;

    public ChatRoomService(ChatRoomRepository roomRepository,
                           ChatParticipantRepository participantRepository,
                           ChatMessageRepository messageRepository,
                           ScheduleRepository scheduleRepository,
                           ChatProperties chatProperties,
                           ChatRoomAccessService access) {
        this.roomRepository = roomRepository;
        this.participantRepository = participantRepository;
        this.messageRepository = messageRepository;
        this.scheduleRepository = scheduleRepository;
        this.chatProperties = chatProperties;
        this.access = access;
    }

    /**
     * 생성 또는 조회.
     *
     * 처리 순서:
     * 1) 같은 일정으로 방이 이미 있으면, 호출자가 그 방의 소유자인지 확인 후 그대로 반환한다
     *    (created=false).
     * 2) 없으면 일정을 조회한다. 일정이 없으면 CHAT_SCHEDULE_NOT_FOUND.
     * 3) 일정 소유자와 호출자가 다르면 CHAT_NOT_OWNER(개설은 소유자만).
     * 4) 만료 시각을 종료일+유예일의 KST 23:59:59 로 계산해 방을 만들고, 호출자를 OWNER
     *    참가자로 등록한 뒤 반환한다(created=true).
     *
     * result.created 로 컨트롤러가 201(신규)/200(기존) 상태를 구분한다.
     */
    @Transactional
    public RoomResult createOrGetRoom(Long scheduleId, Long userId) {
        ChatRoom existing = roomRepository.findByScheduleId(scheduleId).orElse(null);
        if (existing != null) {
            // 이미 방이 있으면 소유자든 멤버든 ACTIVE 참가자에게 그 방을 돌려준다(비참가자는 403).
            access.requireActiveParticipant(existing.getRoomId(), userId);
            return new RoomResult(access.toRoomResponse(existing), false);
        }

        ScheduleEntity schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_SCHEDULE_NOT_FOUND));
        if (schedule.getUserId() == null || !schedule.getUserId().equals(userId)) {
            throw new CustomException(ErrorCode.CHAT_NOT_OWNER);
        }

        OffsetDateTime expiresAt = schedule.getDateEnd()
                .plusDays(chatProperties.getExpiryGraceDays())
                .atTime(23, 59, 59)
                .atZone(KST)
                .toOffsetDateTime();

        ChatRoom room = new ChatRoom(scheduleId, userId, schedule.getTitle(), expiresAt);
        roomRepository.save(room);
        participantRepository.save(
                new ChatParticipant(room.getRoomId(), userId, ChatParticipant.Role.OWNER));
        return new RoomResult(access.toRoomResponse(room), true);
    }

    /** roomId 로 방 단건 조회. 호출자는 ACTIVE 참가자여야 한다. */
    @Transactional(readOnly = true)
    public RoomResponse getRoom(Long roomId, Long userId) {
        ChatRoom room = access.requireRoom(roomId);
        access.requireActiveParticipant(roomId, userId);
        return access.toRoomResponse(room);
    }

    /** 일정 식별자로 방 조회. 호출자는 ACTIVE 참가자여야 한다. */
    @Transactional(readOnly = true)
    public RoomResponse getRoomBySchedule(Long scheduleId, Long userId) {
        ChatRoom room = roomRepository.findByScheduleId(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHAT_ROOM_NOT_FOUND));
        access.requireActiveParticipant(room.getRoomId(), userId);
        return access.toRoomResponse(room);
    }

    /**
     * 내가 ACTIVE 로 참가 중인 방 목록.
     *
     * 각 방의 안 읽은 개수는 방의 메시지 순번이 1 부터 빈틈없이 증가하는 성질을 이용해,
     * 최신 순번(next_seq)에서 내 마지막 읽은 순번을 뺀 값(음수면 0)으로 즉시 구한다.
     * 마지막 메시지 미리보기는 최신 메시지 본문을 사용한다.
     */
    @Transactional(readOnly = true)
    public List<RoomSummary> listMyRooms(Long userId) {
        List<ChatParticipant> memberships =
                participantRepository.findByUserIdAndStatus(userId, ChatParticipant.Status.ACTIVE);
        List<RoomSummary> summaries = new ArrayList<>();
        for (ChatParticipant membership : memberships) {
            ChatRoom room = roomRepository.findById(membership.getRoomId()).orElse(null);
            if (room == null) {
                continue;
            }
            long latestSeq = room.getNextSeq();
            long unread = Math.max(0, latestSeq - membership.getLastReadMessageSeq());
            String lastMessage = messageRepository.findTopByRoomIdOrderBySeqDesc(room.getRoomId())
                    .map(ChatMessage::getContent)
                    .orElse(null);
            summaries.add(new RoomSummary(
                    room.getRoomId(),
                    room.getScheduleId(),
                    room.getTitle(),
                    room.isReadOnly(),
                    unread,
                    lastMessage,
                    latestSeq));
        }
        return summaries;
    }

    /**
     * createOrGetRoom 결과. response 는 방 응답, created 는 이번 호출에서 새로 만들었는지 여부.
     * 컨트롤러가 created 로 HTTP 201/200 을 구분한다.
     */
    public record RoomResult(RoomResponse response, boolean created) {
    }
}

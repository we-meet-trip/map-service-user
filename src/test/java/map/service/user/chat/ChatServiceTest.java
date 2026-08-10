package map.service.user.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import map.service.user.chat.dto.InvitePreview;
import map.service.user.chat.dto.InviteResponse;
import map.service.user.chat.dto.RoomResponse;
import map.service.user.chat.dto.RoomSummary;
import map.service.user.chat.entity.ChatMessage;
import map.service.user.chat.entity.ChatParticipant;
import map.service.user.chat.entity.ChatRoom;
import map.service.user.chat.repository.ChatMessageRepository;
import map.service.user.chat.repository.ChatParticipantRepository;
import map.service.user.chat.repository.ChatRoomRepository;
import map.service.user.chat.service.ChatInviteService;
import map.service.user.chat.service.ChatInviteTokenFactory;
import map.service.user.chat.service.ChatParticipantService;
import map.service.user.chat.service.ChatPresenceService;
import map.service.user.chat.service.ChatRoomAccessService;
import map.service.user.chat.service.ChatRoomService;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.config.ChatProperties;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.schedule.ScheduleEntity;
import map.service.user.schedule.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * ChatServiceTest — 방/초대/참가자 서비스 로직 통합 테스트 (H2)
 *
 * 3단계 audit: 개설 소유권·생성/조회 멱등·만료 스냅샷, 초대 발급/재발급/폐기/미리보기,
 * 참가(정원 원자 검사·이미 참가·재입장·강퇴 후 재참가·만료 방), 나가기(소유자=방 종료),
 * 강퇴(비소유자 거부·소유자 대상 거부), 참가자 아닌 접근 거부를 실제 리포지토리로 검증한다.
 *
 * 서비스는 @DataJpaTest 가 스캔하지 않으므로 실제 리포지토리를 주입해 직접 구성한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("Chat 서비스 통합 테스트 (H2)")
class ChatServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long OWNER = 1L;

    @Autowired private ChatRoomRepository roomRepository;
    @Autowired private ChatParticipantRepository participantRepository;
    @Autowired private ChatMessageRepository messageRepository;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatProperties props = new ChatProperties();
    private final ChatInviteTokenFactory tokenFactory = new ChatInviteTokenFactory();

    private ChatRoomAccessService access;
    private ChatRoomService roomService;
    private ChatInviteService inviteService;
    private ChatParticipantService participantService;

    @BeforeEach
    void setUp() {
        access = new ChatRoomAccessService(roomRepository, participantRepository);
        roomService = new ChatRoomService(roomRepository, participantRepository, messageRepository,
                scheduleRepository, props, access);
        inviteService = new ChatInviteService(roomRepository, participantRepository, tokenFactory,
                props, access);
        // presence 는 leave/kick 테스트와 무관하므로 목으로 대체한다.
        participantService = new ChatParticipantService(participantRepository, access,
                org.mockito.Mockito.mock(ChatPresenceService.class), userRepository);
    }

    /** 소유자·종료일을 지정해 일정을 저장하고 schedule_id 를 반환한다. */
    private Long persistSchedule(Long ownerId, LocalDate dateEnd) {
        ScheduleEntity schedule = new ScheduleEntity(
                ownerId, UUID.randomUUID(), "속초 당일치기",
                dateEnd.minusDays(1), dateEnd, objectMapper.createObjectNode(),
                "walk", 9, 18);
        return scheduleRepository.save(schedule).getScheduleId();
    }

    private long createRoomAsOwner(LocalDate dateEnd) {
        Long scheduleId = persistSchedule(OWNER, dateEnd);
        return roomService.createOrGetRoom(scheduleId, OWNER).response().roomId();
    }

    // ── 개설 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("개설 — 소유자가 만들면 방과 OWNER 참가자가 생성(created=true)")
    void createRoom_ownerHappyPath() {
        Long scheduleId = persistSchedule(OWNER, LocalDate.now().plusDays(3));

        ChatRoomService.RoomResult result = roomService.createOrGetRoom(scheduleId, OWNER);

        assertThat(result.created()).isTrue();
        assertThat(result.response().ownerId()).isEqualTo(OWNER);
        assertThat(result.response().participantCount()).isEqualTo(1);
        ChatParticipant owner = participantRepository
                .findByRoomIdAndUserId(result.response().roomId(), OWNER).orElseThrow();
        assertThat(owner.getRole()).isEqualTo(ChatParticipant.Role.OWNER);
    }

    @Test
    @DisplayName("개설 — 같은 일정 재요청 시 기존 방 반환(created=false)")
    void createRoom_idempotent() {
        Long scheduleId = persistSchedule(OWNER, LocalDate.now().plusDays(3));
        long first = roomService.createOrGetRoom(scheduleId, OWNER).response().roomId();

        ChatRoomService.RoomResult again = roomService.createOrGetRoom(scheduleId, OWNER);

        assertThat(again.created()).isFalse();
        assertThat(again.response().roomId()).isEqualTo(first);
    }

    @Test
    @DisplayName("개설 — 일정 소유자가 아니면 CHAT_NOT_OWNER")
    void createRoom_nonOwner() {
        Long scheduleId = persistSchedule(OWNER, LocalDate.now().plusDays(3));

        assertThatThrownBy(() -> roomService.createOrGetRoom(scheduleId, 999L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_NOT_OWNER);
    }

    @Test
    @DisplayName("개설 — 일정이 없으면 CHAT_SCHEDULE_NOT_FOUND")
    void createRoom_missingSchedule() {
        assertThatThrownBy(() -> roomService.createOrGetRoom(123456L, OWNER))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_SCHEDULE_NOT_FOUND);
    }

    @Test
    @DisplayName("개설 — 만료 시각은 종료일+유예일의 KST 23:59:59 로 고정")
    void createRoom_expirySnapshot() {
        LocalDate dateEnd = LocalDate.of(2026, 7, 20);
        Long scheduleId = persistSchedule(OWNER, dateEnd);

        RoomResponse response = roomService.createOrGetRoom(scheduleId, OWNER).response();

        OffsetDateTime expected = dateEnd.plusDays(props.getExpiryGraceDays())
                .atTime(23, 59, 59).atZone(KST).toOffsetDateTime();
        assertThat(response.expiresAt().toInstant()).isEqualTo(expected.toInstant());
    }

    // ── 목록 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("목록 — 아무도 말하지 않은 방은 마지막 대화 시각이 비어 있고 인원은 1")
    void listMyRooms_emptyRoom() {
        createRoomAsOwner(LocalDate.now().plusDays(3));

        RoomSummary summary = roomService.listMyRooms(OWNER).get(0);

        assertThat(summary.lastMessage()).isNull();
        assertThat(summary.lastMessageAt()).isNull();
        assertThat(summary.participantCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("목록 — 마지막 대화의 본문·시각과 현재 인원수를 함께 준다")
    void listMyRooms_carriesPreviewAndHeadcount() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));
        String token = inviteService.generateOrRotate(roomId, OWNER).token();
        inviteService.join(token, 2L);
        messageRepository.save(ChatMessage.text(roomId, 1L, OWNER, "먼저 한 말"));
        ChatMessage latest = messageRepository.save(ChatMessage.text(roomId, 2L, 2L, "나중에 한 말"));

        RoomSummary summary = roomService.listMyRooms(OWNER).get(0);

        // 목록은 최근 대화 순으로 정렬하므로 가장 마지막 것이 실려야 한다.
        assertThat(summary.lastMessage()).isEqualTo("나중에 한 말");
        assertThat(summary.lastMessageAt()).isEqualTo(latest.getCreatedAt());
        assertThat(summary.participantCount()).isEqualTo(2);
    }

    // ── 초대 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("초대 — 발급/재발급 시 해시 저장 + 버전 증가, 구 토큰 무효")
    void invite_generateRotate() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));

        InviteResponse first = inviteService.generateOrRotate(roomId, OWNER);
        assertThat(first.version()).isEqualTo(1);
        assertThat(roomRepository.findByInviteTokenHash(tokenFactory.hash(first.token()))).isPresent();

        InviteResponse second = inviteService.generateOrRotate(roomId, OWNER);
        assertThat(second.version()).isEqualTo(2);
        // 재발급 후 구 토큰의 해시로는 방을 찾을 수 없다.
        assertThat(roomRepository.findByInviteTokenHash(tokenFactory.hash(first.token()))).isEmpty();
        assertThat(roomRepository.findByInviteTokenHash(tokenFactory.hash(second.token()))).isPresent();
    }

    @Test
    @DisplayName("초대 — 발급은 소유자만(참가한 MEMBER 여도 비소유자면 CHAT_NOT_OWNER)")
    void invite_generate_nonOwner() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));
        String token = inviteService.generateOrRotate(roomId, OWNER).token();
        inviteService.join(token, 2L);

        assertThatThrownBy(() -> inviteService.generateOrRotate(roomId, 2L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_NOT_OWNER);
    }

    @Test
    @DisplayName("초대 — 폐기 시 해시 제거 + 버전 증가, 구 토큰으로 미리보기는 CHAT_INVITE_INVALID")
    void invite_revoke() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));
        InviteResponse issued = inviteService.generateOrRotate(roomId, OWNER);

        inviteService.revoke(roomId, OWNER);

        ChatRoom room = roomRepository.findById(roomId).orElseThrow();
        assertThat(room.getInviteTokenHash()).isNull();
        assertThat(room.getInviteTokenVersion()).isEqualTo(2);
        assertThatThrownBy(() -> inviteService.preview(issued.token()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_INVITE_INVALID);
    }

    @Test
    @DisplayName("초대 — 미리보기는 참가 없이 joinable 상태를 반영")
    void invite_preview() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));
        InviteResponse issued = inviteService.generateOrRotate(roomId, OWNER);

        assertThat(inviteService.preview(issued.token()).joinable()).isTrue();
    }

    @Test
    @DisplayName("초대 — 방이 한참 뒤에 닫히면 링크의 7일이 유효기간이 된다")
    void invite_ttlWinsOverDistantRoomExpiry() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(60));

        InviteResponse issued = inviteService.generateOrRotate(roomId, OWNER);

        ChatRoom room = roomRepository.findById(roomId).orElseThrow();
        assertThat(issued.expiresAt())
                .isCloseTo(OffsetDateTime.now().plusDays(7), within(1, ChronoUnit.MINUTES))
                .isBefore(room.getExpiresAt());
    }

    @Test
    @DisplayName("초대 — 방이 먼저 닫히면 방 만료가 유효기간이 된다")
    void invite_roomExpiryWinsOverLongTtl() {
        // 링크 수명을 방보다 길게 잡아, 이른 쪽이 이기는지만 본다.
        props.setInviteTtlDays(30);
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));

        InviteResponse issued = inviteService.generateOrRotate(roomId, OWNER);

        ChatRoom room = roomRepository.findById(roomId).orElseThrow();
        assertThat(issued.expiresAt()).isEqualTo(room.getExpiresAt());
    }

    @Test
    @DisplayName("초대 — 유효기간이 지난 링크로 참가하면 CHAT_INVITE_REVOKED")
    void invite_join_expiredLink() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(30));
        String raw = tokenFactory.newRawToken();
        ChatRoom room = roomRepository.findById(roomId).orElseThrow();
        room.rotateInvite(tokenFactory.hash(raw), OffsetDateTime.now().minusMinutes(1));
        roomRepository.saveAndFlush(room);

        assertThatThrownBy(() -> inviteService.join(raw, 2L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_INVITE_REVOKED);
    }

    @Test
    @DisplayName("초대 — 유효기간이 지난 링크의 미리보기는 거부 대신 joinable=false 와 방 정보를 준다")
    void invite_preview_expiredLink() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(30));
        String raw = tokenFactory.newRawToken();
        ChatRoom room = roomRepository.findById(roomId).orElseThrow();
        room.rotateInvite(tokenFactory.hash(raw), OffsetDateTime.now().minusMinutes(1));
        roomRepository.saveAndFlush(room);

        InvitePreview preview = inviteService.preview(raw);

        // 받은 사람이 무엇에 초대받았는지는 알아야 다음 행동을 정할 수 있다.
        assertThat(preview.joinable()).isFalse();
        assertThat(preview.roomId()).isEqualTo(roomId);
        assertThat(preview.title()).isEqualTo("속초 당일치기");
    }

    @Test
    @DisplayName("초대 — 폐기하면 링크 유효기간도 함께 비워진다")
    void invite_revoke_clearsExpiry() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));
        inviteService.generateOrRotate(roomId, OWNER);
        assertThat(roomRepository.findById(roomId).orElseThrow().getInviteExpiresAt()).isNotNull();

        inviteService.revoke(roomId, OWNER);

        assertThat(roomRepository.findById(roomId).orElseThrow().getInviteExpiresAt()).isNull();
    }

    // ── 참가 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("참가 — 링크로 MEMBER 로 참가하면 ACTIVE 참가자 증가")
    void join_member() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));
        String token = inviteService.generateOrRotate(roomId, OWNER).token();

        RoomResponse response = inviteService.join(token, 2L);

        assertThat(response.participantCount()).isEqualTo(2);
        assertThat(participantRepository.findByRoomIdAndUserId(roomId, 2L))
                .get().extracting(ChatParticipant::isActive).isEqualTo(true);
    }

    @Test
    @DisplayName("참가 — 이미 ACTIVE 참가자면 CHAT_ALREADY_PARTICIPANT")
    void join_alreadyParticipant() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));
        String token = inviteService.generateOrRotate(roomId, OWNER).token();
        inviteService.join(token, 2L);

        assertThatThrownBy(() -> inviteService.join(token, 2L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ALREADY_PARTICIPANT);
    }

    @Test
    @DisplayName("참가 — 정원(10명) 도달 시 CHAT_ROOM_FULL")
    void join_capacityReached() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));
        // 소유자 1명 + MEMBER 9명 = 10명(정원)
        for (long uid = 2L; uid <= 10L; uid++) {
            participantRepository.save(new ChatParticipant(roomId, uid, ChatParticipant.Role.MEMBER));
        }
        String token = inviteService.generateOrRotate(roomId, OWNER).token();

        assertThatThrownBy(() -> inviteService.join(token, 11L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_FULL);
    }

    @Test
    @DisplayName("참가 — 나갔던 참가자는 재입장(reactivate)")
    void join_leftThenRejoin() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));
        String token = inviteService.generateOrRotate(roomId, OWNER).token();
        inviteService.join(token, 2L);
        participantService.leave(roomId, 2L);

        inviteService.join(token, 2L);

        assertThat(participantRepository.findByRoomIdAndUserId(roomId, 2L))
                .get().extracting(ChatParticipant::getStatus)
                .isEqualTo(ChatParticipant.Status.ACTIVE);
    }

    @Test
    @DisplayName("참가 — 강퇴된 참가자는 같은 링크로 재참가 불가(CHAT_KICKED)")
    void join_kickedCannotRejoin() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));
        String token = inviteService.generateOrRotate(roomId, OWNER).token();
        inviteService.join(token, 2L);
        participantService.kick(roomId, OWNER, 2L);

        assertThatThrownBy(() -> inviteService.join(token, 2L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_KICKED);
    }

    @Test
    @DisplayName("참가 — 만료된 방은 CHAT_ROOM_EXPIRED")
    void join_expiredRoom() {
        // 이미 만료 시각이 지난 방을 직접 구성하고 유효 토큰을 심는다.
        ChatRoom room = new ChatRoom(persistSchedule(OWNER, LocalDate.now().plusDays(1)),
                OWNER, "만료방", OffsetDateTime.now().minusDays(1));
        String raw = tokenFactory.newRawToken();
        // 링크는 살려 둔다. 링크가 먼저 죽으면 방 만료가 아니라 링크 만료로 걸려
        // 이 테스트가 이름과 다른 것을 재게 된다.
        room.rotateInvite(tokenFactory.hash(raw), OffsetDateTime.now().plusDays(7));
        roomRepository.save(room);
        participantRepository.save(new ChatParticipant(room.getRoomId(), OWNER, ChatParticipant.Role.OWNER));

        assertThatThrownBy(() -> inviteService.join(raw, 2L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_ROOM_EXPIRED);
    }

    @Test
    @DisplayName("참가 — 입장 시 읽음 포인터가 현재 nextSeq 로 초기화(입장 전 메시지 미읽음 제외)")
    void join_setsReadPointerToCurrentSeq() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));
        // 입장 전 메시지 3건을 시뮬레이션(방 seq 를 3 까지 진행)
        ChatRoom room = roomRepository.findById(roomId).orElseThrow();
        room.allocateNextSeq();
        room.allocateNextSeq();
        room.allocateNextSeq();
        roomRepository.save(room);
        String token = inviteService.generateOrRotate(roomId, OWNER).token();

        inviteService.join(token, 2L);

        ChatParticipant joiner = participantRepository.findByRoomIdAndUserId(roomId, 2L).orElseThrow();
        assertThat(joiner.getLastReadMessageSeq()).isEqualTo(3);
    }

    // ── 나가기 / 강퇴 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("나가기 — MEMBER 는 LEFT 로 전환(방 유지)")
    void leave_member() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));
        String token = inviteService.generateOrRotate(roomId, OWNER).token();
        inviteService.join(token, 2L);

        participantService.leave(roomId, 2L);

        assertThat(participantRepository.findByRoomIdAndUserId(roomId, 2L))
                .get().extracting(ChatParticipant::getStatus).isEqualTo(ChatParticipant.Status.LEFT);
        assertThat(roomRepository.findById(roomId).orElseThrow().isReadOnly()).isFalse();
    }

    @Test
    @DisplayName("나가기 — 소유자가 나가면 방 종료(read_only)")
    void leave_owner_closesRoom() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));

        participantService.leave(roomId, OWNER);

        assertThat(participantRepository.findByRoomIdAndUserId(roomId, OWNER))
                .get().extracting(ChatParticipant::getStatus).isEqualTo(ChatParticipant.Status.LEFT);
        assertThat(roomRepository.findById(roomId).orElseThrow().isReadOnly()).isTrue();
    }

    @Test
    @DisplayName("강퇴 — 소유자가 MEMBER 를 KICKED 로 전환")
    void kick_ownerKicksMember() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));
        String token = inviteService.generateOrRotate(roomId, OWNER).token();
        inviteService.join(token, 2L);

        participantService.kick(roomId, OWNER, 2L);

        assertThat(participantRepository.findByRoomIdAndUserId(roomId, 2L))
                .get().extracting(ChatParticipant::getStatus).isEqualTo(ChatParticipant.Status.KICKED);
    }

    @Test
    @DisplayName("강퇴 — 비소유자는 CHAT_NOT_OWNER")
    void kick_nonOwner() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));
        String token = inviteService.generateOrRotate(roomId, OWNER).token();
        inviteService.join(token, 2L);
        inviteService.join(token, 3L);

        assertThatThrownBy(() -> participantService.kick(roomId, 2L, 3L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_NOT_OWNER);
    }

    @Test
    @DisplayName("강퇴 — 소유자를 대상으로 하면 CHAT_NOT_PARTICIPANT")
    void kick_targetOwner() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));

        assertThatThrownBy(() -> participantService.kick(roomId, OWNER, OWNER))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_NOT_PARTICIPANT);
    }

    @Test
    @DisplayName("접근 — 참가자가 아니면 방 조회 시 CHAT_NOT_PARTICIPANT")
    void getRoom_nonParticipant() {
        long roomId = createRoomAsOwner(LocalDate.now().plusDays(3));

        assertThatThrownBy(() -> roomService.getRoom(roomId, 777L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CHAT_NOT_PARTICIPANT);
    }
}

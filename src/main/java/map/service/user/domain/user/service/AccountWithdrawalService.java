package map.service.user.domain.user.service;

import java.util.ArrayList;
import java.util.List;
import map.service.user.chat.entity.ChatParticipant;
import map.service.user.chat.repository.ChatParticipantRepository;
import map.service.user.chat.service.ChatParticipantService;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.jwt.JwtService;
import map.service.user.schedule.ScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AccountWithdrawalService — 회원 탈퇴
 *
 * 공급자 권한 철회가 성공한 뒤, 사용자 잠금 안에서 개인 데이터와 계정을 지운다.
 * 본인 소유 채팅방은 먼저 닫고 일정/소유자 연결을 제거하여 다른 사람의 메시지를
 * 보존한다. 본인 메시지·참가 이력·일정·추천 결과는 제거하고, 지연된 추천 이벤트를
 * 막는 UUID 전용 취소 표시만 남긴다. JWT는 계정 부재로 즉시 거부하며 Redis 정리는
 * 커밋 뒤 수행한다. 계정 탈퇴는 방 나가기의 이력 보존 정책과 별개의 작업이다.
 */
@Service
public class AccountWithdrawalService {

    private final UserRepository userRepository;
    private final ScheduleRepository scheduleRepository;
    private final ChatParticipantRepository participantRepository;
    private final ChatParticipantService participantService;
    private final JwtService jwtService;
    private final map.service.user.recommend.RecommendJobStore jobs;
    private final map.service.user.recommend.DraftStore drafts;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final map.service.user.domain.auth.apple.AppleAccountService appleAccounts;

    public AccountWithdrawalService(UserRepository userRepository,
                                    ScheduleRepository scheduleRepository,
                                    ChatParticipantRepository participantRepository,
                                    ChatParticipantService participantService,
                                    JwtService jwtService,
                                    map.service.user.recommend.RecommendJobStore jobs,
                                    map.service.user.recommend.DraftStore drafts,
                                    org.springframework.jdbc.core.JdbcTemplate jdbc,
                                    map.service.user.domain.auth.apple.AppleAccountService appleAccounts) {
        this.jobs = jobs; this.drafts = drafts; this.jdbc = jdbc; this.appleAccounts = appleAccounts;
        this.userRepository = userRepository;
        this.scheduleRepository = scheduleRepository;
        this.participantRepository = participantRepository;
        this.participantService = participantService;
        this.jwtService = jwtService;
    }

    /**
     * 탈퇴 처리.
     *
     * @param userId         토큰에서 온 사용자 식별자
     * @param rawAccessToken 이번 요청이 들고 온 접근 토큰(Bearer 접두어 제거된 값)
     * @return 이번 탈퇴로 종료된 방 식별자 목록. 호출자가 커밋 뒤에 종료를 알린다.
     */
    @Transactional
    public List<Long> withdraw(Long userId, String rawAccessToken) {
        User user = requireUser(userId);

        appleAccounts.revokeForWithdrawal(userId);
        List<Long> closedRoomIds = leaveAllRooms(userId);
        // Flush managed room/participant changes before JDBC detaches and erases those rows.
        userRepository.flush();
        List<Long> ownedRooms = jdbc.queryForList(
                "SELECT room_id FROM user_service.chat_rooms WHERE owner_id=? FOR UPDATE", Long.class, userId);
        for (Long roomId : ownedRooms) {
            if (!closedRoomIds.contains(roomId)) closedRoomIds.add(roomId);
        }
        jdbc.update("UPDATE user_service.chat_messages SET content=NULL, system_payload=NULL "
                + "WHERE type='SYSTEM' AND room_id IN (SELECT room_id FROM user_service.chat_rooms WHERE owner_id=?) "
                + "AND system_payload->>'kind'='VIEW_ITINERARY'", userId);
        jdbc.update("UPDATE user_service.chat_messages SET content=NULL, system_payload=NULL "
                + "WHERE system_payload->>'user_id'=?", userId.toString());
        jdbc.update("UPDATE user_service.chat_rooms SET schedule_id=NULL, owner_id=NULL, title='종료된 대화', "
                + "read_only=TRUE, invite_revoked=TRUE, invite_token_hash=NULL WHERE owner_id=?", userId);
        // Retain only non-identifying moderation outcome/audit metadata. Erase freeform
        // descriptions even when another reporter may have named the withdrawn author.
        jdbc.update("UPDATE user_service.moderation_reports SET description=NULL, request_fingerprint=NULL, "
                + "reporter_id=CASE WHEN reporter_id=? THEN NULL ELSE reporter_id END, reported_user_id=NULL, "
                + "message_id=NULL, room_id=NULL, message_seq=NULL, schedule_id=NULL, recommend_job_id=NULL "
                + "WHERE reporter_id=? OR reported_user_id=?", userId, userId, userId);
        jdbc.update("DELETE FROM user_service.user_blocks WHERE blocker_id=? OR blocked_user_id=?", userId, userId);
        jdbc.update("DELETE FROM user_service.chat_restrictions WHERE user_id=?", userId);
        java.util.List<String> jobIds = jobs.eraseOwnedJobs(userId);
        // Erase authored personal content, while other participants keep their own conversation.
        jdbc.update("UPDATE user_service.chat_messages SET sender_id=NULL, content=NULL, system_payload=NULL WHERE sender_id=?", userId);
        jdbc.update("DELETE FROM user_service.chat_membership_intervals WHERE user_id=?", userId);
        jdbc.update("DELETE FROM user_service.chat_participants WHERE user_id=?", userId);
        scheduleRepository.deleteByUserId(userId);
        userRepository.delete(user);
        // No cache error can restore DB access after erasure; TTL remains the final cleanup bound.
        Runnable cacheCleanup = () -> {
            try { jwtService.blacklistAccessToken(rawAccessToken); } catch (RuntimeException ignored) { }
            for (String id : jobIds) {
                try { drafts.delete(id); } catch (RuntimeException ignored) { }
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { cacheCleanup.run(); }
                    });
        } else {
            cacheCleanup.run();
        }

        return closedRoomIds;
    }

    /**
     * 참가 중인 방에서 모두 빠진다.
     *
     * 나가기 경로를 그대로 쓴다. 소유자로 있던 방은 그 안에서 보관 전용으로 바뀌고
     * 여기서는 식별자만 모은다. 방은 다른 사람의 대화를 위해 보존하며, 커밋 뒤
     * 이미 접속한 사람에게 보관 전용으로 바뀌었음을 알린다.
     */
    private List<Long> leaveAllRooms(Long userId) {
        List<Long> closedRoomIds = new ArrayList<>();
        List<ChatParticipant> active =
                participantRepository.findByUserIdAndStatus(userId, ChatParticipant.Status.ACTIVE);
        for (ChatParticipant participant : active) {
            if (participantService.leave(participant.getRoomId(), userId)) {
                closedRoomIds.add(participant.getRoomId());
            }
        }
        return closedRoomIds;
    }

    private User requireUser(Long userId) {
        if (userId == null) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }
}

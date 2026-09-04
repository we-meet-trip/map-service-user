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
 * 화면이 약속한 것은 "탈퇴 시 모든 정보가 삭제됩니다" 이므로 사용자 행 자체를 지운다.
 * 상태 컬럼으로 숨기는 방식은 약속과 다르고, 되살릴 수 있다는 뜻이 되어 쓰지 않는다.
 *
 * 지우는 순서가 중요하다:
 *   ① 참가 중인 채팅방에서 먼저 빠진다. 사용자 행을 먼저 지우면 참가행이 ACTIVE 인 채
 *      주인 없이 남아, 남의 방 참가자 목록에 이름 없는 자리로 계속 세어진다.
 *   ② 일정을 지운다. schedules 는 users 를 외래키로 걸지 않아 자동으로 따라오지 않는다.
 *      일정이 사라지면 그 일정의 채팅방은 외래키 연쇄로 함께 사라진다.
 *   ③ 사용자 행을 지운다. 로그인 수단·기기·갱신 토큰·친구·여행 기록은 전부 users 를
 *      ON DELETE CASCADE 로 걸고 있어 이 한 번으로 정리된다.
 *   ④ 지금 들고 온 접근 토큰을 막는다. 갱신 토큰은 ③에서 사라져 재발급이 불가능하지만,
 *      접근 토큰은 서버에 보관하지 않아 만료 전까지 스스로는 무효가 되지 않는다.
 *
 * 남기는 것: 다른 사람 방의 대화 기록과 참가 이력. 나가기·강퇴와 같은 소프트 제거이며,
 * 남은 사람들의 대화 맥락을 지우지 않기 위해서다. 발신자 식별자만 남고 이름은 붙지
 * 않으므로 지워진 사람의 정보가 드러나지도 않는다.
 */
@Service
public class AccountWithdrawalService {

    private final UserRepository userRepository;
    private final ScheduleRepository scheduleRepository;
    private final ChatParticipantRepository participantRepository;
    private final ChatParticipantService participantService;
    private final JwtService jwtService;

    public AccountWithdrawalService(UserRepository userRepository,
                                    ScheduleRepository scheduleRepository,
                                    ChatParticipantRepository participantRepository,
                                    ChatParticipantService participantService,
                                    JwtService jwtService) {
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

        List<Long> closedRoomIds = leaveAllRooms(userId);
        scheduleRepository.deleteByUserId(userId);
        userRepository.delete(user);
        jwtService.blacklistAccessToken(rawAccessToken);

        return closedRoomIds;
    }

    /**
     * 참가 중인 방에서 모두 빠진다.
     *
     * 나가기 경로를 그대로 쓴다. 소유자로 있던 방은 그 안에서 보관 전용으로 바뀌고
     * 여기서는 식별자만 모은다. 그 방들은 대개 ②의 일정 삭제로 함께 사라지지만,
     * 이미 접속해 있는 사람에게는 사라짐이 전달되지 않으므로 종료를 알려야 한다.
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
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }
}

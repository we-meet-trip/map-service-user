package map.service.user.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import map.service.user.chat.entity.ChatParticipant;
import map.service.user.chat.repository.ChatParticipantRepository;
import map.service.user.chat.service.ChatParticipantService;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.domain.user.service.AccountWithdrawalService;
import map.service.user.global.exception.CustomException;
import map.service.user.global.jwt.JwtService;
import map.service.user.schedule.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * AccountWithdrawalServiceTest — 탈퇴 단위 테스트
 *
 * 지키려는 계약은 순서다. 방에서 빠지기 전에 사용자 행을 지우면 남의 방에
 * 주인 없는 참가행이 ACTIVE 로 남고, 일정을 지우기 전에 지우면 주인 없는
 * 일정이 남는다. 둘 다 조용히 남기 때문에 순서를 검사로 못박아 둔다.
 */
@DisplayName("AccountWithdrawalService 탈퇴")
class AccountWithdrawalServiceTest {

    private UserRepository userRepository;
    private ScheduleRepository scheduleRepository;
    private ChatParticipantRepository participantRepository;
    private ChatParticipantService participantService;
    private JwtService jwtService;
    private AccountWithdrawalService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        scheduleRepository = mock(ScheduleRepository.class);
        participantRepository = mock(ChatParticipantRepository.class);
        participantService = mock(ChatParticipantService.class);
        jwtService = mock(JwtService.class);
        service = new AccountWithdrawalService(userRepository, scheduleRepository,
                participantRepository, participantService, jwtService,
                mock(map.service.user.recommend.RecommendJobStore.class),
                mock(map.service.user.recommend.DraftStore.class),
                mock(org.springframework.jdbc.core.JdbcTemplate.class),
                mock(map.service.user.domain.auth.apple.AppleAccountService.class));
    }

    private User user() {
        return User.builder()
                .email("a@b.c")
                .nickname("테스터")
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(false)
                .build();
    }

    private ChatParticipant participantOf(Long roomId) {
        return new ChatParticipant(roomId, 1L, ChatParticipant.Role.MEMBER);
    }

    @Test
    @DisplayName("방에서 먼저 빠지고, 일정을 지운 뒤, 사용자를 지운다")
    void removesInOrder() {
        User user = user();
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(participantRepository.findByUserIdAndStatus(1L, ChatParticipant.Status.ACTIVE))
                .thenReturn(List.of(participantOf(10L)));

        service.withdraw(1L, "tok");

        InOrder order = inOrder(participantService, scheduleRepository, userRepository);
        order.verify(participantService).leave(10L, 1L);
        order.verify(scheduleRepository).deleteByUserId(1L);
        order.verify(userRepository).delete(user);
    }

    @Test
    @DisplayName("소유자로 있던 방만 종료 대상으로 돌려준다")
    void reportsOnlyClosedRooms() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user()));
        when(participantRepository.findByUserIdAndStatus(1L, ChatParticipant.Status.ACTIVE))
                .thenReturn(List.of(participantOf(10L), participantOf(20L)));
        when(participantService.leave(10L, 1L)).thenReturn(true);
        when(participantService.leave(20L, 1L)).thenReturn(false);

        assertThat(service.withdraw(1L, "tok")).containsExactly(10L);
    }

    @Test
    @DisplayName("참가 중인 방이 없어도 탈퇴는 끝난다")
    void withdrawsWithoutRooms() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user()));
        when(participantRepository.findByUserIdAndStatus(1L, ChatParticipant.Status.ACTIVE))
                .thenReturn(List.of());

        assertThat(service.withdraw(1L, "tok")).isEmpty();
        verify(userRepository).delete(any(User.class));
    }

    @Test
    @DisplayName("지금 들고 온 접근 토큰을 막는다 — 만료 전까지 스스로 무효가 되지 않는다")
    void blocksPresentedAccessToken() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user()));
        when(participantRepository.findByUserIdAndStatus(1L, ChatParticipant.Status.ACTIVE))
                .thenReturn(List.of());

        service.withdraw(1L, "tok");

        verify(jwtService).blacklistAccessToken("tok");
    }

    @Test
    @DisplayName("없는 사용자면 아무것도 지우지 않는다")
    void unknownUserDeletesNothing() {
        when(userRepository.findByIdForUpdate(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(9L, "tok"))
                .isInstanceOf(CustomException.class);

        verify(scheduleRepository, never()).deleteByUserId(any());
        verify(userRepository, never()).delete(any(User.class));
    }
    @Test
    void flushesManagedMembershipChangesBeforeErasingDetachedRows() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user()));
        service.withdraw(1L, "token");
        var order = org.mockito.Mockito.inOrder(userRepository, scheduleRepository);
        order.verify(userRepository).findByIdForUpdate(1L);
        order.verify(userRepository).flush();
        order.verify(scheduleRepository).deleteByUserId(1L);
        order.verify(userRepository).delete(org.mockito.ArgumentMatchers.any());
    }

}

package map.service.user.policy;

import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServicePolicyServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final ServicePolicyAcceptanceRepository records = mock(ServicePolicyAcceptanceRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-06T15:00:00Z"), ZoneOffset.UTC);
    private final ServicePolicyService service = new ServicePolicyService(users, records, clock);
    private User user;
    @BeforeEach void setUp() {
        user = User.builder().nickname("synthetic-policy-test").authProvider(AuthProvider.EMAIL).build();
        ReflectionTestUtils.setField(user, "id", 7L);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(records.findById(7L)).thenReturn(Optional.empty());
        when(records.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }
    private ServicePolicyService.AcceptRequest request() {
        return new ServicePolicyService.AcceptRequest("2026-09-07", "2026-09-07.1", true, true, true);
    }
    private void birthday(String date) { ReflectionTestUtils.setField(user, "birthDate", LocalDate.parse(date)); }
    private void assertCode(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOf(CustomException.class)
                .satisfies(error -> assertThat(((CustomException) error).getErrorCode()).isEqualTo(code));
    }
    @Test void existingUserHasNoFabricatedAcceptance() {
        assertThat(service.status(7L).accepted()).isFalse();
        assertThat(service.status(7L).ageEligible()).isNull();
        assertCode(() -> service.requireEligible(7L), ErrorCode.AGE_INFORMATION_REQUIRED);
        verify(records, never()).save(any());
    }
    @Test void missingBirthdayCannotBeReplacedByAnAdultCheckbox() {
        assertCode(() -> service.accept(7L, request()), ErrorCode.AGE_INFORMATION_REQUIRED);
        assertThat(service.status(7L).accepted()).isFalse();
        assertThat(service.status(7L).ageEligible()).isNull();
        verify(records, never()).save(any());
    }
    @Test void previouslyAcceptedMissingBirthdayIsBlockedOnEveryNewRequest() {
        when(records.findById(7L)).thenReturn(Optional.of(new ServicePolicyAcceptance(user,
                "2026-09-07", "2026-09-07.1", OffsetDateTime.now(clock))));
        assertThat(service.status(7L).accepted()).isFalse();
        assertCode(() -> service.requireEligible(7L), ErrorCode.AGE_INFORMATION_REQUIRED);
        birthday("2000-01-01");
        service.requireEligible(7L);
        birthday("2012-01-01");
        assertCode(() -> service.requireEligible(7L), ErrorCode.AGE_RESTRICTED);
    }
    @Test void kstEighteenthBirthdayIsEligibleWhileUtcDateIsStillYesterday() {
        birthday("2008-09-07");
        assertThat(service.accept(7L, request()).accepted()).isTrue();
        var beforeMidnight = new ServicePolicyService(users, records,
                Clock.fixed(Instant.parse("2026-09-06T14:59:59Z"), ZoneOffset.UTC));
        assertCode(() -> beforeMidnight.accept(7L, request()), ErrorCode.AGE_RESTRICTED);
    }
    @Test void minorDeclarationDoesNotOverrideKnownBirthday() {
        birthday("2008-09-08");
        assertThat(service.status(7L).ageEligible()).isFalse();
        assertCode(() -> service.accept(7L, request()), ErrorCode.AGE_RESTRICTED);
        verify(records, never()).save(any());
    }
    @Test void knownMinorIsRejectedEvenAfterPreviousAcceptance() {
        when(records.findById(7L)).thenReturn(Optional.of(new ServicePolicyAcceptance(user,
                "2026-09-07", "2026-09-07.1", OffsetDateTime.now(clock))));
        birthday("2012-01-01");
        assertCode(() -> service.requireEligible(7L), ErrorCode.AGE_RESTRICTED);
    }
    @Test void staleVersionAndMissingOrFalseConfirmationDoNotWrite() {
        assertCode(() -> service.accept(7L, new ServicePolicyService.AcceptRequest("old", "2026-09-07.1", true, true, true)), ErrorCode.POLICY_VERSION_MISMATCH);
        assertCode(() -> service.accept(7L, new ServicePolicyService.AcceptRequest("2026-09-07", "2026-09-07.1", null, true, true)), ErrorCode.POLICY_ACCEPTANCE_INVALID);
        assertCode(() -> service.accept(7L, new ServicePolicyService.AcceptRequest("2026-09-07", "2026-09-07.1", true, false, true)), ErrorCode.POLICY_ACCEPTANCE_INVALID);
        assertCode(() -> service.accept(7L, new ServicePolicyService.AcceptRequest("2026-09-07", "2026-09-07.1", true, true, false)), ErrorCode.POLICY_ACCEPTANCE_INVALID);
        verify(records, never()).save(any());
    }
    @Test void repeatedAcceptancePreservesOriginalTimestampAndRequiresAccountLock() {
        birthday("2000-01-01");
        OffsetDateTime original = OffsetDateTime.now(clock).minusHours(1);
        when(records.findById(7L)).thenReturn(Optional.of(new ServicePolicyAcceptance(user, "2026-09-07", "2026-09-07.1", original)));
        assertThat(service.accept(7L, request()).acceptedAt()).isEqualTo(original);
        verify(users).findByIdForUpdate(7L);
        verify(records, never()).save(any());
    }
    @Test void previousPolicyRequiresNewExplicitAcceptance() {
        birthday("2000-01-01");
        when(records.findById(7L)).thenReturn(Optional.of(new ServicePolicyAcceptance(user, "old", "old", OffsetDateTime.now(clock).minusDays(1))));
        assertCode(() -> service.requireEligible(7L), ErrorCode.SERVICE_POLICY_REQUIRED);
        assertThat(service.accept(7L, request()).accepted()).isTrue();
        service.requireEligible(7L);
    }
}

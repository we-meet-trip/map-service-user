package map.service.user.policy;

import java.util.Optional;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.jwt.JwtService;
import map.service.user.recommend.RecommendJobRepository;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiConsentServiceTest {
    final UserRepository users = mock(UserRepository.class);
    final AiConsentRepository consents = mock(AiConsentRepository.class);
    final AiJobConsentRepository jobs = mock(AiJobConsentRepository.class);
    final ServicePolicyService policy = mock(ServicePolicyService.class);
    final JwtService jwt = mock(JwtService.class);
    final AiConsentService service = new AiConsentService(users, consents, jobs, mock(RecommendJobRepository.class), policy, jwt);

    private void current(long revision) {
        var row = mock(AiConsentRepository.Current.class);
        when(row.getAccepted()).thenReturn(true);
        when(row.getPolicyVersion()).thenReturn(AiConsentService.VERSION);
        when(row.getRevision()).thenReturn(revision);
        when(consents.current("7:trip")).thenReturn(Optional.of(row));
    }
    @Test void absentOrRevokedConsentNeverBecomesAccepted() {
        when(consents.current("7:trip")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.open(7L, "trip")).hasFieldOrPropertyWithValue("errorCode", ErrorCode.AI_CONSENT_REQUIRED);
        verifyNoInteractions(jobs);
    }
    @Test void persistenceFailureIsSafe503WithoutStorageDetails() {
        when(consents.current("7:trip")).thenThrow(new IllegalStateException("synthetic-secret-storage-address"));
        assertThatThrownBy(() -> service.open(7L, "trip")).hasFieldOrPropertyWithValue("errorCode", ErrorCode.AI_CONSENT_UNAVAILABLE)
                .hasMessageNotContaining("synthetic-secret");
    }
    @Test void revokeRegrantEpochCannotRevivePreviousWork() {
        current(3);
        assertThatThrownBy(() -> service.requireCurrent(new AiConsentService.Permit(7L,"trip",1,false,null)))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AI_CONSENT_CHANGED);
    }
    @Test void originalJwtExpiryAndLogoutArePreservedBeforeQueuedProviderWork() {
        for (ErrorCode code : new ErrorCode[]{ErrorCode.EXPIRED_TOKEN, ErrorCode.BLACKLISTED_TOKEN, ErrorCode.INVALID_TOKEN}) {
            doThrow(new CustomException(code)).when(jwt).validateAccessToken("synthetic-token");
            var permit = new AiConsentService.Permit(7L,"trip",1,false,"synthetic-token");
            assertThatThrownBy(() -> service.requireCurrent(permit)).hasFieldOrPropertyWithValue("errorCode", code);
            assertThat(permit.toString()).doesNotContain("synthetic-token");
        }
        verifyNoInteractions(consents);
    }
    @Test void staleOrMalformedSettingsCannotRestoreConsent() {
        User user = mock(User.class); when(user.getId()).thenReturn(7L);
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        var row = new AiConsent(user, "trip"); row.revision = 2;
        when(consents.findById("7:trip")).thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service.grant(7L,"trip",new AiConsentService.Grant(AiConsentService.VERSION,true,false,0L)))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AI_CONSENT_CONFLICT);
        assertThatThrownBy(() -> service.grant(7L,"trip",new AiConsentService.Grant(AiConsentService.VERSION,true,true,2L)))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POLICY_ACCEPTANCE_INVALID);
        verify(consents, never()).saveAndFlush(any());
    }
}

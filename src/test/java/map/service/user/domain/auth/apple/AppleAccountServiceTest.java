package map.service.user.domain.auth.apple;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.Optional;
import map.service.user.domain.auth.service.AuthService;
import map.service.user.domain.auth.service.TokenRevokeService;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.crypto.PayloadCipher;
import map.service.user.global.crypto.TestPayloadCiphers;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AppleAccountServiceTest {
    final AppleChallengeStore challenge = mock(AppleChallengeStore.class);
    final AppleProviderClient apple = mock(AppleProviderClient.class);
    final AppleAccountRepository links = mock(AppleAccountRepository.class);
    final UserRepository users = mock(UserRepository.class);
    final AuthService auth = mock(AuthService.class);
    final TokenRevokeService revoke = mock(TokenRevokeService.class);
    final PayloadCipher cipher = TestPayloadCiphers.enabled();
    final AppleSettings settings = new AppleSettings(true,"test-client","test-team","test-key","test-private");
    AppleAccountService service() { return new AppleAccountService(settings,challenge,apple,links,users,auth,revoke,cipher); }
    String aad() { return PayloadCipher.aad("apple_accounts","refresh_token","7"); }

    @Test void withdrawalRevokesDecryptedCredentialAndProviderFailureKeepsIt() {
        String encrypted = cipher.encrypt("test-refresh",aad());
        AppleAccount account = new AppleAccount(7L,"apple-sub",encrypted);
        when(links.findById(7L)).thenReturn(Optional.of(account));
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
                .when(apple).revoke("test-refresh");
        assertThatThrownBy(() -> service().revokeForWithdrawal(7L)).isInstanceOf(ResponseStatusException.class);
        assertThat(account.getRefreshTokenCiphertext()).isEqualTo(encrypted);
        doNothing().when(apple).revoke("test-refresh");
        service().revokeForWithdrawal(7L);
        assertThat(account.getRefreshTokenCiphertext()).isNull();
        verify(apple,times(2)).revoke("test-refresh");
    }
    @Test void nonAppleWithdrawalDoesNotCallProviderAndRevokedGrantRevokesSessions() {
        service().revokeForWithdrawal(8L);
        verifyNoInteractions(apple);
        AppleAccount account = new AppleAccount(7L,"apple-sub",cipher.encrypt("test-refresh",aad()));
        when(links.findById(7L)).thenReturn(Optional.of(account));
        when(apple.remainsAuthorized("test-refresh","apple-sub")).thenReturn(false);
        service().checkAuthorization(7L);
        verify(revoke).revokeAllForUser(7L);
        assertThat(account.getRefreshTokenCiphertext()).isNull();
    }
    @Test void configurationAndEncryptionMustBothBePresentBeforeChallenge() {
        var disabled = new AppleAccountService(new AppleSettings(false,"","","",""),challenge,apple,links,users,auth,revoke,cipher);
        assertThatThrownBy(disabled::challenge).isInstanceOf(ResponseStatusException.class);
        var plaintext = new AppleAccountService(settings,challenge,apple,links,users,auth,revoke,TestPayloadCiphers.disabled());
        assertThatThrownBy(plaintext::challenge).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(challenge);
    }
    @Test void verifiedSubjectCreatesSeparateAppleIdentityAndEncryptsCredential() {
        when(challenge.consume("state")).thenReturn("nonce");
        var identity = new AppleProviderClient.Identity("apple-sub", "existing@map.test", true);
        when(apple.verify("identity", "nonce")).thenReturn(identity);
        when(apple.exchange("code")).thenReturn(new AppleProviderClient.Tokens("exchanged", "test-refresh"));
        when(apple.verify("exchanged", "nonce")).thenReturn(identity);
        when(users.saveAndFlush(any())).thenAnswer(invocation -> {
            User created = invocation.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(created,"id",7L);
            return created;
        });
        service().login(new AppleController.Login("identity","code","state","Apple tester"));
        var created = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(auth).buildAuthResponse(created.capture());
        assertThat(created.getValue().getAuthProvider()).isEqualTo(AuthProvider.APPLE);
        assertThat(created.getValue().getEmail()).isNull();
        verify(users,never()).findByEmail(anyString());
        var link = org.mockito.ArgumentCaptor.forClass(AppleAccount.class);
        verify(links).saveAndFlush(link.capture());
        assertThat(link.getValue().getSubject()).isEqualTo("apple-sub");
        assertThat(link.getValue().getRefreshTokenCiphertext()).doesNotContain("test-refresh");
        assertThat(cipher.decrypt(link.getValue().getRefreshTokenCiphertext(),aad())).isEqualTo("test-refresh");
    }
    @Test void mismatchedAuthorizationCodeCannotCreateOrLinkAnAccount() {
        when(challenge.consume("state")).thenReturn("nonce");
        when(apple.verify("identity","nonce")).thenReturn(new AppleProviderClient.Identity("subject-a",null,false));
        when(apple.exchange("code")).thenReturn(new AppleProviderClient.Tokens("exchanged","test-refresh"));
        when(apple.verify("exchanged","nonce")).thenReturn(new AppleProviderClient.Identity("subject-b",null,false));
        assertThatThrownBy(() -> service().login(new AppleController.Login("identity","code","state",null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> assertThat(ex.getStatusCode().value()).isEqualTo(401));
        verifyNoInteractions(links,users,auth);
    }

}

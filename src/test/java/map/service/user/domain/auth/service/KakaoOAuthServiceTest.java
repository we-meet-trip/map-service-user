package map.service.user.domain.auth.service;

import map.service.user.domain.auth.dto.request.KakaoLoginRequest;
import map.service.user.domain.auth.dto.response.AuthResponse;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.OAuthAccount;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.OAuthAccountRepository;
import map.service.user.domain.user.repository.UserDeviceRepository;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.config.KakaoProperties;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Executes the real service and both RestClient requests against an in-process provider stub. */
class KakaoOAuthServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final OAuthAccountRepository accounts = mock(OAuthAccountRepository.class);
    private final UserDeviceRepository devices = mock(UserDeviceRepository.class);
    private final AuthService auth = mock(AuthService.class);
    private final KakaoProperties properties = new KakaoProperties();
    private MockRestServiceServer provider;
    private KakaoOAuthService service;
    private final User existing = User.builder().email("synthetic@example.invalid")
            .nickname("fixture").authProvider(AuthProvider.EMAIL).build();
    private final AuthResponse response = AuthResponse.builder().accessToken("synthetic-session").build();

    @BeforeEach
    void setup() {
        properties.setAppId(4242L);
        properties.setTokenInfoUri("https://provider.example.invalid/token-info");
        properties.setUserInfoUri("https://provider.example.invalid/me");
        RestClient.Builder builder = RestClient.builder();
        provider = MockRestServiceServer.bindTo(builder).build();
        service = new KakaoOAuthService(properties, users, accounts, devices, auth, builder.build());
    }

    /** 토큰 정보 조회와 사용자 정보 조회를 차례로 세운다. 토큰의 회원번호는 123 으로 고정한다. */
    private void providerReturns(String email, String id) {
        tokenInfoReturns(123L, 4242L);
        String account = email == null ? "{}" : "{\"email\":\"" + email
                + "\",\"email_needs_agreement\":false,\"is_email_verified\":true,\"is_email_valid\":true}";
        provider.expect(requestTo(properties.getUserInfoUri())).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer synthetic-provider-token"))
                .andRespond(withSuccess("{\"id\":" + id + ",\"kakao_account\":" + account + "}", MediaType.APPLICATION_JSON));
    }

    private void tokenInfoReturns(long userId, long appId) {
        provider.expect(requestTo(properties.getTokenInfoUri())).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer synthetic-provider-token"))
                .andRespond(withSuccess("{\"id\":" + userId + ",\"expires_in\":21599,\"app_id\":" + appId + "}",
                        MediaType.APPLICATION_JSON));
    }

    private void expectRejected() {
        assertThatThrownBy(this::login).isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException)e).getErrorCode()).isEqualTo(ErrorCode.KAKAO_TOKEN_REJECTED);
        verifyNoInteractions(users, accounts, auth, devices);
    }

    private AuthResponse login() {
        var request = new KakaoLoginRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(request, "accessToken", "synthetic-provider-token");
        try { return service.processLogin(request); }
        finally { provider.verify(); }
    }

    private void expectConflict() {
        assertThatThrownBy(this::login).isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException)e).getErrorCode()).isEqualTo(ErrorCode.KAKAO_ACCOUNT_CONFLICT);
        verifyNoInteractions(auth, devices);
    }

    @Test
    void linkedProviderIdentityLogsInWithoutSelectingByEmail() {
        providerReturns("different@example.invalid", "123");
        when(accounts.findByProviderAndProviderUserId(AuthProvider.KAKAO, 123L)).thenReturn(Optional.of(
                OAuthAccount.builder().user(existing).provider(AuthProvider.KAKAO).providerUserId(123L).build()));
        when(auth.buildAuthResponse(existing)).thenReturn(response);
        assertThat(login()).isSameAs(response);
        verifyNoInteractions(users);
        verify(accounts, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(AuthProvider.class)
    void unlinkedProviderCannotClaimExistingEmailForAnyAccountType(AuthProvider type) {
        providerReturns("synthetic@example.invalid", "123");
        when(users.findByEmail("synthetic@example.invalid")).thenReturn(Optional.of(
                User.builder().email("synthetic@example.invalid").authProvider(type).build()));
        expectConflict();
        verify(users, never()).save(any());
        verify(accounts, never()).saveAndFlush(any());
    }

    @Test
    void newProviderWithoutEmailCreatesItsOwnUserAndLink() {
        providerReturns(null, "123");
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(auth.buildAuthResponse(any())).thenReturn(response);
        assertThat(login()).isSameAs(response);
        var saved = ArgumentCaptor.forClass(OAuthAccount.class);
        verify(accounts).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getProviderUserId()).isEqualTo(123L);
        assertThat(saved.getValue().getUser().getEmail()).isNull();
        assertThat(saved.getValue().getUser().isEmailVerified()).isFalse();
        verify(users, never()).findByEmail(any());
    }

    @Test
    void newUniqueEmailIsStoredWithoutReusingAnotherAccount() {
        providerReturns("synthetic@example.invalid", "123");
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(auth.buildAuthResponse(any())).thenReturn(response);
        assertThat(login()).isSameAs(response);
        var saved = ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("synthetic@example.invalid");
        verify(accounts).saveAndFlush(any());
    }

    @Test
    void concurrentEmailCollisionRollsBackInsteadOfLookingUpTheWinner() {
        providerReturns("synthetic@example.invalid", "123");
        when(users.save(any())).thenThrow(new DataIntegrityViolationException("synthetic collision"));
        expectConflict();
        verify(users, times(1)).findByEmail("synthetic@example.invalid");
        verify(accounts, never()).saveAndFlush(any());
    }

    @Test
    void noEmailConstraintFailureNeverLooksUpNullEmail() {
        providerReturns(null, "123");
        when(users.save(any())).thenThrow(new DataIntegrityViolationException("synthetic collision"));
        expectConflict();
        verify(users, never()).findByEmail(any());
    }

    @Test
    void concurrentProviderCollisionReturnsConflictWithoutQueryingAbortedTransaction() {
        providerReturns(null, "123");
        when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(accounts.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("synthetic collision"));
        expectConflict();
        verify(accounts, times(1)).findByProviderAndProviderUserId(AuthProvider.KAKAO, 123L);
        verify(users, never()).findByEmail(any());
    }

    @Test
    void providerWithoutIdentityDoesNotCreateUserOrSession() {
        providerReturns(null, "null");
        assertThatThrownBy(this::login).isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException)e).getErrorCode()).isEqualTo(ErrorCode.KAKAO_USER_INFO_FAILED);
        verifyNoInteractions(users, accounts, auth, devices);
    }

    @Test
    void tokenIssuedToAnotherApplicationIsRefusedBeforeAnyLookup() {
        tokenInfoReturns(123L, 9999L);
        expectRejected();
    }

    @Test
    void refusedTokenIsNotTreatedAsAProviderOutage() {
        provider.expect(requestTo(properties.getTokenInfoUri()))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectRejected();
    }

    @Test
    void providerOutageAsksForARetryInsteadOfSigningTheUserOut() {
        provider.expect(requestTo(properties.getTokenInfoUri()))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(this::login).isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException)e).getErrorCode())
                .isEqualTo(ErrorCode.KAKAO_TOKEN_INFO_UNAVAILABLE);
        verifyNoInteractions(users, accounts, auth, devices);
    }

    @Test
    void twoLookupsDisagreeingOnTheMemberNumberAreRefused() {
        tokenInfoReturns(123L, 4242L);
        provider.expect(requestTo(properties.getUserInfoUri()))
                .andRespond(withSuccess("{\"id\":456,\"kakao_account\":{}}", MediaType.APPLICATION_JSON));
        expectRejected();
    }

    @Test
    void disabledProviderRefusesLoginWithoutCallingKakao() {
        properties.setAppId(null);
        assertThatThrownBy(this::login).isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException)e).getErrorCode()).isEqualTo(ErrorCode.KAKAO_NOT_CONFIGURED);
        verifyNoInteractions(users, accounts, auth, devices);
    }
}

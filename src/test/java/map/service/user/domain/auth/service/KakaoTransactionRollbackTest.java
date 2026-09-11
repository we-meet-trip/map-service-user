package map.service.user.domain.auth.service;

import map.service.user.domain.auth.dto.request.KakaoLoginRequest;
import map.service.user.domain.user.repository.*;
import map.service.user.global.config.KakaoProperties;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true")
@ActiveProfiles("test")
@Import(KakaoOAuthService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class KakaoTransactionRollbackTest {
    @Autowired KakaoOAuthService service;
    @Autowired UserRepository users;
    @MockitoBean OAuthAccountRepository accounts;
    @MockitoBean UserDeviceRepository devices;
    @MockitoBean AuthService auth;
    @MockitoBean KakaoProperties properties;
    @MockitoBean RestClient restClient;

    @Test void providerCollisionRollsBackTheRealInsertedUserAndNeverIssuesSession() {
        long before = users.count();
        var builder = RestClient.builder();
        var stub = MockRestServiceServer.bindTo(builder).build();
        var actualClient = builder.build();
        when(restClient.post()).thenAnswer(invocation -> actualClient.post());
        when(restClient.get()).thenAnswer(invocation -> actualClient.get());
        when(properties.getClientId()).thenReturn("synthetic");
        when(properties.getRedirectUri()).thenReturn("https://test.example.invalid/api/v1/auth/kakao/callback");
        when(properties.getTokenUri()).thenReturn("https://provider.example.invalid/token");
        when(properties.getUserInfoUri()).thenReturn("https://provider.example.invalid/me");
        stub.expect(requestTo("https://provider.example.invalid/token"))
                .andRespond(withSuccess("{\"access_token\":\"synthetic\"}", MediaType.APPLICATION_JSON));
        stub.expect(requestTo("https://provider.example.invalid/me"))
                .andRespond(withSuccess("{\"id\":123,\"kakao_account\":{}}", MediaType.APPLICATION_JSON));
        when(accounts.saveAndFlush(any())).thenAnswer(invocation -> {
            assertThat(users.count()).isEqualTo(before + 1);
            throw new DataIntegrityViolationException("synthetic provider race");
        });
        var request = new KakaoLoginRequest();
        ReflectionTestUtils.setField(request, "code", "synthetic");
        assertThatThrownBy(() -> service.processLogin(request)).isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.KAKAO_ACCOUNT_CONFLICT);
        stub.verify();
        assertThat(users.count()).isEqualTo(before);
        verifyNoInteractions(auth, devices);
    }
}

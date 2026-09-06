package map.service.user.domain.auth.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import map.service.user.domain.auth.dto.request.TokenRefreshRequest;
import map.service.user.domain.user.entity.*;
import map.service.user.domain.user.repository.*;
import map.service.user.global.jwt.JwtService;
import map.service.user.global.exception.CustomException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent H2 transactions contend on the same user lock; no server or Redis connection. */
@DataJpaTest
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(TokenRevokeService.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class RefreshSessionConcurrencyTest {
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository tokens;
    @Autowired UserDeviceRepository devices;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired TokenRevokeService revoker;

    @Test void providerRevocationWaitsForInFlightRotationAndRevokesItsDescendant() throws Exception {
        User user = users.save(User.builder().email(UUID.randomUUID()+"@map.test").nickname("isolated")
                .authProvider(AuthProvider.EMAIL).emailVerified(true).build());
        String raw = UUID.randomUUID().toString(), sid = UUID.randomUUID().toString();
        tokens.save(RefreshToken.builder().user(user).tokenHash(AuthService.sha256Hex(raw))
                .sessionId(sid).expiresAt(OffsetDateTime.now().plusDays(1)).build());
        JwtService jwt = mock(JwtService.class);
        when(jwt.generateAccessToken(any(), anyString())).thenReturn("test-access");
        when(jwt.generateRawRefreshToken()).thenAnswer(i -> UUID.randomUUID().toString());
        when(jwt.getRefreshTokenExpirySeconds()).thenReturn(86400L);
        AuthService service = new AuthService(users, tokens, devices,
                mock(org.springframework.security.crypto.password.PasswordEncoder.class), jwt, revoker);
        TokenRefreshRequest request = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue("{\"refreshToken\":\""+raw+"\"}", TokenRefreshRequest.class);
        CountDownLatch locked = new CountDownLatch(1), rotate = new CountDownLatch(1), revoking = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> rotation = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                users.findByIdForUpdate(user.getId()).orElseThrow();
                locked.countDown();
                try { if (!rotate.await(5, TimeUnit.SECONDS)) throw new AssertionError("rotation barrier timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return service.refreshTokens(request);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> revocation = executor.submit(() -> {
                revoking.countDown(); revoker.revokeAllForUser(user.getId());
            });
            assertThat(revoking.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> revocation.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            rotate.countDown();
            rotation.get(10, TimeUnit.SECONDS); revocation.get(10, TimeUnit.SECONDS);
            assertThat(tokens.existsBySessionIdAndRevokedAtIsNullAndExpiresAtAfter(sid, OffsetDateTime.now())).isFalse();
        } finally {
            rotate.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS);
            tokens.deleteAll(tokens.findAll().stream().filter(t -> t.getUser().getId().equals(user.getId())).toList());
            users.deleteById(user.getId());
        }
    }

    @Test void onlyOneRotationSucceedsAndReuseRevokesItsDescendant() throws Exception {
        User user = users.save(User.builder().email(UUID.randomUUID()+"@map.test").nickname("isolated")
                .authProvider(AuthProvider.EMAIL).emailVerified(true).build());
        String raw = UUID.randomUUID().toString();
        String sid = UUID.randomUUID().toString();
        tokens.save(RefreshToken.builder().user(user).tokenHash(AuthService.sha256Hex(raw))
                .sessionId(sid).expiresAt(OffsetDateTime.now().plusDays(1)).build());
        JwtService jwt = mock(JwtService.class);
        when(jwt.generateAccessToken(any(), anyString())).thenReturn("test-access");
        when(jwt.generateRawRefreshToken()).thenAnswer(i -> UUID.randomUUID().toString());
        when(jwt.getRefreshTokenExpirySeconds()).thenReturn(86400L);
        AuthService service = new AuthService(users, tokens, devices,
                mock(org.springframework.security.crypto.password.PasswordEncoder.class), jwt,
                mock(TokenRevokeService.class));
        TokenRefreshRequest request = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue("{\"refreshToken\":\""+raw+"\"}", TokenRefreshRequest.class);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> rotate = () -> {
                start.await();
                return new TransactionTemplate(transactionManager).execute(status -> {
                    // Match refreshTokens(noRollbackFor=CustomException): commit reuse revocation.
                    try { service.refreshTokens(request); return true; }
                    catch (CustomException denied) { return false; }
                });
            };
            Future<Boolean> first=executor.submit(rotate), second=executor.submit(rotate);
            start.countDown();
            assertThat(java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(tokens.existsBySessionIdAndRevokedAtIsNullAndExpiresAtAfter(sid, OffsetDateTime.now())).isFalse();
        } finally { executor.shutdownNow();
            tokens.deleteAll(tokens.findAll().stream().filter(t -> t.getUser().getId().equals(user.getId())).toList());
            users.deleteById(user.getId()); }
    }
}

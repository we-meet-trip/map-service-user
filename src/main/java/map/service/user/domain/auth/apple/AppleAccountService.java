package map.service.user.domain.auth.apple;

import lombok.RequiredArgsConstructor;
import map.service.user.domain.auth.dto.response.AuthResponse;
import map.service.user.domain.auth.service.AuthService;
import map.service.user.domain.auth.service.TokenRevokeService;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.crypto.PayloadCipher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AppleAccountService {
    private final AppleSettings settings;
    private final AppleChallengeStore challenges;
    private final AppleProviderClient apple;
    private final AppleAccountRepository links;
    private final UserRepository users;
    private final AuthService auth;
    private final TokenRevokeService revoke;
    private final PayloadCipher cipher;

    public Map<String,String> challenge() { requireConfigured(); return challenges.issue(); }
    private void requireConfigured() {
        settings.requireConfigured();
        if (!cipher.isEnabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Apple credential encryption required");
    }
    @Transactional
    public AuthResponse login(AppleController.Login request) {
        requireConfigured();
        String nonce=challenges.consume(request.state());
        var identity=apple.verify(request.identityToken(),nonce);
        AppleProviderClient.Tokens tokens;
        try { tokens=apple.exchange(request.authorizationCode()); }
        catch (AppleProviderClient.AppleRevoked e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid Apple authorization code"); }
        var exchanged=apple.verify(tokens.idToken(),nonce);
        if (!identity.subject().equals(exchanged.subject())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Apple identity mismatch");
        var linked=links.findBySubject(identity.subject());
        User user;
        if (linked.isPresent()) {
            user=users.findById(linked.get().getUserId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Account unavailable"));
            linked.get().refresh(cipher.encrypt(tokens.refreshToken(),aad(user.getId())));
        } else {
            // Apple subject is the identity; matching email never links another account.
            String nickname=request.nickname()==null || request.nickname().isBlank() ? "여행자" : request.nickname().trim();
            user=users.saveAndFlush(User.builder().nickname(nickname).authProvider(AuthProvider.APPLE).emailVerified(false).build());
            links.saveAndFlush(new AppleAccount(user.getId(),identity.subject(),cipher.encrypt(tokens.refreshToken(),aad(user.getId()))));
        }
        return auth.buildAuthResponse(user);
    }
    @Transactional
    public void revokeForWithdrawal(Long userId) {
        links.findById(userId).ifPresent(link -> {
            if (link.getRefreshTokenCiphertext()!=null) {
                requireConfigured(); apple.revoke(cipher.decrypt(link.getRefreshTokenCiphertext(),aad(userId)));
                link.refresh(null);
            }
        });
    }
    @Transactional
    public void checkAuthorization(Long userId) {
        var link=links.findById(userId).orElse(null);
        if (link==null || link.getRefreshTokenCiphertext()==null) return;
        boolean authorized=apple.remainsAuthorized(cipher.decrypt(link.getRefreshTokenCiphertext(),aad(userId)),link.getSubject());
        if (!authorized) { revoke.revokeAllForUser(userId); link.refresh(null); }
        else link.refresh(link.getRefreshTokenCiphertext());
    }
    private static String aad(Long id) { return PayloadCipher.aad("apple_accounts","refresh_token",id.toString()); }
}

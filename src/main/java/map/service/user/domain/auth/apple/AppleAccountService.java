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
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AppleAccountService.class);

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
    /**
     * 탈퇴하면서 Apple 쪽 토큰도 함께 거둔다.
     *
     * 실패해도 예외를 밖으로 내지 않는다. 이 호출은 탈퇴 트랜잭션 안에서
     * 도는데, 여기서 예외가 넘어가면 트랜잭션이 통째로 되돌아가 계정이
     * 지워지지 않는다. 그러면 Apple 장애나 자격 오설정이 곧 "앱에서 계정을
     * 지울 수 없음"이 된다 — 지울 수 있어야 한다는 것이 더 앞선 요구다.
     *
     * 거두지 못했으면 암호문을 그대로 남겨 나중에 다시 시도할 수 있게 둔다.
     */
    @Transactional
    public void revokeForWithdrawal(Long userId) {
        links.findById(userId).ifPresent(link -> {
            if (link.getRefreshTokenCiphertext()==null) return;
            try {
                requireConfigured();
                apple.revoke(cipher.decrypt(link.getRefreshTokenCiphertext(),aad(userId)));
                link.refresh(null);
            } catch (RuntimeException failed) {
                log.warn("Apple 토큰 회수 실패 — 탈퇴는 그대로 진행한다. userId={} 사유={}",
                        userId, failed.toString());
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

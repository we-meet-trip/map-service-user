package map.service.user.domain.auth.service;

import lombok.RequiredArgsConstructor;
import map.service.user.domain.user.repository.RefreshTokenRepository;
import map.service.user.domain.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class TokenRevokeService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    /**
     * 공급자 권한 철회 시 해당 사용자의 모든 refresh session을 폐기한다.
     * REQUIRES_NEW: 외부 트랜잭션 롤백 여부와 무관하게 폐기는 항상 커밋됨 (보안 우선).
     * 호출자는 같은 사용자 잠금을 잡은 외부 트랜잭션에서 이 메서드를 호출하지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllForUser(Long userId) {
        // Serialize with refresh/logout, so a pending rotation cannot insert a survivor.
        if (userRepository.findByIdForUpdate(userId).isEmpty()) return;
        refreshTokenRepository.revokeAllByUserId(userId, OffsetDateTime.now());
    }
}

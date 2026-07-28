package map.service.user.domain.auth.service;

import lombok.RequiredArgsConstructor;
import map.service.user.domain.user.repository.RefreshTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class TokenRevokeService {

    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * 재사용 탐지 시 해당 사용자의 모든 refresh token 즉시 폐기.
     * REQUIRES_NEW: 외부 트랜잭션 롤백 여부와 무관하게 폐기는 항상 커밋됨 (보안 우선).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllForUser(Long userId) {
        refreshTokenRepository.revokeAllByUserId(userId, OffsetDateTime.now());
    }
}

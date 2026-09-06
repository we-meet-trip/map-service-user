package map.service.user.domain.user.repository;

import map.service.user.domain.user.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Query("select r.user.id from RefreshToken r where r.tokenHash = :hash")
    Optional<Long> findUserIdByTokenHash(@org.springframework.data.repository.query.Param("hash") String hash);

    boolean existsBySessionIdAndRevokedAtIsNullAndExpiresAtAfter(String sessionId, OffsetDateTime now);

    @Modifying
    @Query("update RefreshToken r set r.revokedAt = :now where r.user.id = :uid "
            + "and (r.sessionId = :sid or r.tokenHash = :sid) and r.revokedAt is null")
    void revokeSession(@org.springframework.data.repository.query.Param("uid") Long uid,
                       @org.springframework.data.repository.query.Param("sid") String sid,
                       @org.springframework.data.repository.query.Param("now") OffsetDateTime now);

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revokedAt = :now WHERE r.user.id = :userId AND r.revokedAt IS NULL")
    void revokeAllByUserId(Long userId, OffsetDateTime now);
}

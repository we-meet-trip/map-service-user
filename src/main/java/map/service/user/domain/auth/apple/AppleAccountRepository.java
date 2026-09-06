package map.service.user.domain.auth.apple;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface AppleAccountRepository extends JpaRepository<AppleAccount,Long> {
    Optional<AppleAccount> findBySubject(String subject);
    List<AppleAccount> findTop100ByRefreshTokenCiphertextIsNotNullAndCheckedAtBeforeOrderByCheckedAtAsc(OffsetDateTime before);
}

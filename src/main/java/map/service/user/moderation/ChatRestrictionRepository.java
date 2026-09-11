package map.service.user.moderation;

import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRestrictionRepository extends JpaRepository<ChatRestriction, Long> {
    boolean existsByUserIdAndRestrictedUntilAfter(Long user, OffsetDateTime now);
}

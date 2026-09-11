package map.service.user.moderation;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ModerationActionRepository extends JpaRepository<ModerationAction, UUID> {
    List<ModerationAction> findByReportIdOrderByCreatedAtAsc(UUID report);
}

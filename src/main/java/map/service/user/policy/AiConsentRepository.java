package map.service.user.policy;

import java.util.Optional;
import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiConsentRepository extends JpaRepository<AiConsent, String> {
    interface Current {
        String getPolicyVersion(); Boolean getAccepted(); Boolean getIncludeLocation();
        Long getRevision(); OffsetDateTime getUpdatedAt();
    }
    // Scalar projection avoids request EntityManager snapshots after a committed withdrawal.
    @Query("select c.policyVersion as policyVersion, c.accepted as accepted, c.includeLocation as includeLocation, c.revision as revision, c.updatedAt as updatedAt from AiConsent c where c.id = :id")
    Optional<Current> current(@Param("id") String id);
}

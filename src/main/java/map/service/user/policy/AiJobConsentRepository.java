package map.service.user.policy;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface AiJobConsentRepository extends JpaRepository<AiJobConsent, UUID> {}

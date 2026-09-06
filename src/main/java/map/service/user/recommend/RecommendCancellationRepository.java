package map.service.user.recommend;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RecommendCancellationRepository extends JpaRepository<RecommendCancellation, UUID> {}

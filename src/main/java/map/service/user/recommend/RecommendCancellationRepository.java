package map.service.user.recommend;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RecommendCancellationRepository extends JpaRepository<RecommendCancellation, UUID> {
    @org.springframework.data.jpa.repository.Query("select c.jobId from RecommendCancellation c where c.jobId in :ids")
    java.util.List<UUID> findCancelledIds(@org.springframework.data.repository.query.Param("ids") java.util.Collection<UUID> ids);
}

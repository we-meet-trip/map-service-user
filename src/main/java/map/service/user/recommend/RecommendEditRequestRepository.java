package map.service.user.recommend;

import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecommendEditRequestRepository extends JpaRepository<RecommendEditRequest, String> {
    void deleteByJobId(java.util.UUID jobId);
    @Modifying
    @Query("delete from RecommendEditRequest r where r.expiresAt < :now")
    int deleteExpired(@Param("now") OffsetDateTime now);
}

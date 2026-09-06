package map.service.user.recommend;

import jakarta.persistence.*;
import java.util.UUID;

/** Content-free operational suppression of redelivered jobs after account erasure. */
@Entity
@Table(name="recommend_cancellations", schema="user_service")
public class RecommendCancellation {
    @Id @Column(name="job_id") private UUID jobId;
    protected RecommendCancellation() {}
    public RecommendCancellation(UUID jobId) { this.jobId = jobId; }
}

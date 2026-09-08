package map.service.user.policy;

import jakarta.persistence.*;
import java.util.UUID;
import map.service.user.domain.user.entity.User;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "external_ai_job_consents")
public class AiJobConsent {
    @Id @Column(name = "job_id") UUID jobId;
    @Column(name = "user_id", nullable = false) Long userId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) User user;
    @Column(nullable = false) long revision;
    protected AiJobConsent() {}
    AiJobConsent(UUID jobId, User user, long revision) {
        this.jobId = jobId; this.user = user; this.userId = user.getId(); this.revision = revision;
    }
}

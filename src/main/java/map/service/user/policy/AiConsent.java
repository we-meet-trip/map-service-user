package map.service.user.policy;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import map.service.user.domain.user.entity.User;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "external_ai_consents")
public class AiConsent {
    @Id @Column(length = 64) String id;
    @Column(name = "user_id", nullable = false) Long userId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) User user;
    @Column(nullable = false, length = 32) String scope;
    @Column(name = "policy_version", nullable = false, length = 32) String policyVersion;
    @Column(nullable = false) boolean accepted;
    @Column(name = "include_location", nullable = false) boolean includeLocation;
    @Column(nullable = false) long revision;
    @Column(name = "updated_at", nullable = false) OffsetDateTime updatedAt;
    protected AiConsent() {}
    AiConsent(User user, String scope) {
        this.user = user; this.userId = user.getId(); this.scope = scope;
        this.id = userId + ":" + scope;
    }
}

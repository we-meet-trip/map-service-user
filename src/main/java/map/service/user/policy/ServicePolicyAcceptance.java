package map.service.user.policy;

import jakarta.persistence.*;
import map.service.user.domain.user.entity.User;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.OffsetDateTime;

@Entity
@Table(name = "service_policy_acceptances")
public class ServicePolicyAcceptance {
    @Id @Column(name = "user_id")
    private Long userId;
    // The shared primary key enforces one record per account. A read-only
    // ManyToOne avoids Hibernate's special shared-PK OneToOne merge semantics.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;
    @Column(name = "terms_version", nullable = false, length = 32)
    private String termsVersion;
    @Column(name = "privacy_version", nullable = false, length = 32)
    private String privacyVersion;
    @Column(name = "is_18_or_older", nullable = false)
    private boolean adultDeclaration;
    @Column(name = "accepted_at", nullable = false)
    private OffsetDateTime acceptedAt;

    protected ServicePolicyAcceptance() {}
    ServicePolicyAcceptance(User user, String terms, String privacy, OffsetDateTime now) {
        this.user = user;
        // Read-only association creates the FK without @MapsId's implicit
        // persist cascade, which can re-persist an account being deleted.
        this.userId = user.getId();
        accept(terms, privacy, now);
    }
    void accept(String terms, String privacy, OffsetDateTime now) {
        termsVersion = terms;
        privacyVersion = privacy;
        adultDeclaration = true;
        acceptedAt = now;
    }
    boolean matches(String terms, String privacy) {
        return adultDeclaration && terms.equals(termsVersion) && privacy.equals(privacyVersion);
    }
    OffsetDateTime acceptedAt() { return acceptedAt; }
}

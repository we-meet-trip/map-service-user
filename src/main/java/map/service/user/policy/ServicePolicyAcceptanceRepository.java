package map.service.user.policy;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServicePolicyAcceptanceRepository extends JpaRepository<ServicePolicyAcceptance, Long> {
    interface CurrentEligibility {
        Long getUserId();
        LocalDate getBirthDate();
        String getTermsVersion();
        String getPrivacyVersion();
        Boolean getAdultDeclaration();
    }

    // Scalar projection intentionally bypasses a request EntityManager's cached User/receipt.
    @Query("""
            select u.id as userId, u.birthDate as birthDate,
                   a.termsVersion as termsVersion, a.privacyVersion as privacyVersion,
                   a.adultDeclaration as adultDeclaration
            from User u left join ServicePolicyAcceptance a on a.userId = u.id
            where u.id = :userId
            """)
    Optional<CurrentEligibility> findCurrentEligibility(@Param("userId") Long userId);
}

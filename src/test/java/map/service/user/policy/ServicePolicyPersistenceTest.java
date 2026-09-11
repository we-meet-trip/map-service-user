package map.service.user.policy;

import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import jakarta.persistence.EntityManager;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true")
@ActiveProfiles("test")
@Import({ServicePolicyService.class, map.service.user.domain.user.service.UserProfileService.class})
class ServicePolicyPersistenceTest {
    @Autowired UserRepository users;
    @Autowired ServicePolicyAcceptanceRepository records;
    @Autowired ServicePolicyService service;
    @Autowired EntityManager entityManager;
    @Autowired map.service.user.domain.user.service.UserProfileService profiles;
    @Test void explicitAcceptancePersistsOnceAndWithdrawalCascadesOnlyItsOwnRecord() {
        User first = users.saveAndFlush(User.builder().nickname("synthetic-consent-first").authProvider(AuthProvider.EMAIL).birthDate(java.time.LocalDate.of(2000, 1, 1)).build());
        User second = users.saveAndFlush(User.builder().nickname("synthetic-consent-second").authProvider(AuthProvider.EMAIL).birthDate(java.time.LocalDate.of(2000, 1, 1)).build());
        var request = new ServicePolicyService.AcceptRequest(ServicePolicyService.TERMS_VERSION, ServicePolicyService.PRIVACY_VERSION, true, true, true);
        var original = service.accept(first.getId(), request);
        service.accept(first.getId(), request);
        service.accept(second.getId(), request);
        entityManager.flush();
        entityManager.clear();
        assertThat(records.count()).isEqualTo(2);
        assertThat(service.status(first.getId()).accepted()).isTrue();
        // SQL timestamp precision is microseconds in PostgreSQL and the H2 fixture.
        assertThat(service.status(first.getId()).acceptedAt().toInstant())
                .isEqualTo(original.acceptedAt().toInstant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        users.deleteById(first.getId());
        users.flush();
        entityManager.clear();
        assertThat(records.findById(first.getId())).isEmpty();
        assertThat(service.status(second.getId()).accepted()).isTrue();
    }
    @Test void existingReceiptCannotBypassMissingBirthdayAndCorrectionIsReevaluated() {
        User user = users.saveAndFlush(User.builder().nickname("synthetic-dob-required")
                .authProvider(AuthProvider.KAKAO).build());
        records.saveAndFlush(new ServicePolicyAcceptance(user, ServicePolicyService.TERMS_VERSION, ServicePolicyService.PRIVACY_VERSION,
                java.time.OffsetDateTime.now()));
        entityManager.clear();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requireEligible(user.getId()))
                .hasFieldOrPropertyWithValue("errorCode", map.service.user.global.exception.ErrorCode.AGE_INFORMATION_REQUIRED);
        assertThat(service.status(user.getId()).accepted()).isFalse();
        profiles.updateMe(user.getId(), new map.service.user.domain.user.dto.UserUpdateRequest(null, null,
                java.time.LocalDate.of(2000, 1, 1), null, null, null));
        entityManager.flush(); entityManager.clear();
        service.requireEligible(user.getId());
        profiles.updateMe(user.getId(), new map.service.user.domain.user.dto.UserUpdateRequest(null, null,
                java.time.LocalDate.of(2012, 1, 1), null, null, null));
        entityManager.flush(); entityManager.clear();
        assertThat(service.status(user.getId()).accepted()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.requireEligible(user.getId()))
                .hasFieldOrPropertyWithValue("errorCode", map.service.user.global.exception.ErrorCode.AGE_RESTRICTED);
        assertThat(records.count()).isEqualTo(1);
    }

}

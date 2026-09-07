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
@Import(ServicePolicyService.class)
class ServicePolicyPersistenceTest {
    @Autowired UserRepository users;
    @Autowired ServicePolicyAcceptanceRepository records;
    @Autowired ServicePolicyService service;
    @Autowired EntityManager entityManager;
    @Test void explicitAcceptancePersistsOnceAndWithdrawalCascadesOnlyItsOwnRecord() {
        User first = users.saveAndFlush(User.builder().nickname("synthetic-consent-first").authProvider(AuthProvider.EMAIL).build());
        User second = users.saveAndFlush(User.builder().nickname("synthetic-consent-second").authProvider(AuthProvider.EMAIL).build());
        var request = new ServicePolicyService.AcceptRequest("2026-09-07", "2026-09-07", true, true, true);
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
}

package map.service.user.policy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true")
@ActiveProfiles("test")
@Import(ServicePolicyService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ServicePolicyCurrentReadTest {
    @Autowired UserRepository users;
    @Autowired ServicePolicyAcceptanceRepository records;
    @Autowired ServicePolicyService policy;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    private Long ownUserId;

    private User seed(LocalDate birthday) {
        User user = users.saveAndFlush(User.builder().nickname("synthetic-inflight-policy")
                .birthDate(birthday).authProvider(AuthProvider.EMAIL).build());
        ownUserId = user.getId();
        records.saveAndFlush(new ServicePolicyAcceptance(user, ServicePolicyService.TERMS_VERSION,
                ServicePolicyService.PRIVACY_VERSION, OffsetDateTime.now()));
        return user;
    }
    @AfterEach void removeOwnSyntheticRow() {
        if (ownUserId != null) users.deleteById(ownUserId);
    }
    private void rejected(ErrorCode expected) {
        assertThatThrownBy(() -> policy.requireCurrentEligible(ownUserId)).isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", expected);
    }

    @Test void currentAdultIsAllowedAndMissingBirthdayIsStillRejected() {
        seed(null);
        rejected(ErrorCode.AGE_INFORMATION_REQUIRED);
        jdbc.update("update users set birth_date=? where id=?",
                java.sql.Date.valueOf("2000-01-01"), ownUserId);
        policy.requireCurrentEligible(ownUserId);
    }

    @Test void scalarQuerySeesCommittedBirthdayCorrectionDespiteCachedAdultEntity() {
        seed(LocalDate.of(2000, 1, 1));
        var requestTransaction = new TransactionTemplate(transactionManager);
        requestTransaction.executeWithoutResult(ignored -> {
            User cached = users.findById(ownUserId).orElseThrow();
            assertThat(cached.getBirthDate()).isEqualTo(LocalDate.of(2000, 1, 1));
            var correction = new TransactionTemplate(transactionManager);
            correction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            correction.executeWithoutResult(other -> jdbc.update("update users set birth_date=? where id=?",
                    java.sql.Date.valueOf("2012-01-01"), ownUserId));
            assertThat(users.findById(ownUserId).orElseThrow()).isSameAs(cached);
            assertThat(cached.getBirthDate()).isEqualTo(LocalDate.of(2000, 1, 1));
            rejected(ErrorCode.AGE_RESTRICTED);
        });
    }

    @Test void latestReceiptAndAccountExistenceAreRequired() {
        seed(LocalDate.of(2000, 1, 1));
        policy.requireCurrentEligible(ownUserId);
        jdbc.update("update service_policy_acceptances set terms_version='old' where user_id=?", ownUserId);
        rejected(ErrorCode.SERVICE_POLICY_REQUIRED);
        records.deleteById(ownUserId);
        rejected(ErrorCode.SERVICE_POLICY_REQUIRED);
        users.deleteById(ownUserId);
        rejected(ErrorCode.INVALID_TOKEN);
        ownUserId = null;
    }
}

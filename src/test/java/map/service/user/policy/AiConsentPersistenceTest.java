package map.service.user.policy;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.global.exception.ErrorCode;
import map.service.user.global.jwt.JwtService;
import map.service.user.recommend.RecommendJobEntity;
import map.service.user.recommend.RecommendJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true")
@ActiveProfiles("test")
@Import({AiConsentService.class, ServicePolicyService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiConsentPersistenceTest {
    @Autowired UserRepository users;
    @Autowired ServicePolicyAcceptanceRepository policies;
    @Autowired AiConsentRepository records;
    @Autowired AiConsentService ai;
    @Autowired AiJobConsentRepository bindings;
    @Autowired RecommendJobRepository jobs;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JwtService jwt;
    Long userId;
    UUID jobId;
    private User seed() {
        User user = users.saveAndFlush(User.builder().nickname("synthetic-ai-consent").birthDate(LocalDate.of(2000,1,1))
                .authProvider(AuthProvider.EMAIL).build());
        userId = user.getId();
        policies.saveAndFlush(new ServicePolicyAcceptance(user,ServicePolicyService.TERMS_VERSION,
                ServicePolicyService.PRIVACY_VERSION,OffsetDateTime.now()));
        return user;
    }
    private AiConsentService.Status grant(String scope,long revision) {
        return ai.grant(userId,scope,new AiConsentService.Grant(AiConsentService.VERSION,true,false,revision));
    }
    @AfterEach void cleanup() {
        if (userId != null) users.deleteById(userId);
        if (jobId != null) jobs.deleteById(jobId);
    }
    @Test void scopesPersistIndependentlyAndWithdrawalInvalidatesAllOldRevisions() {
        seed();
        assertThat(ai.status(userId)).allMatch(s -> !s.accepted() && s.revision() == 0);
        assertThat(grant("trip",0).revision()).isEqualTo(1);
        assertThat(grant("trip",1).revision()).isEqualTo(1);
        var original = ai.open(userId,"trip");
        assertThat(ai.status(userId).stream().filter(s -> s.scope().equals("vision")).findFirst().orElseThrow().accepted()).isFalse();
        assertThat(ai.revoke(userId,"trip",1).revision()).isEqualTo(2);
        assertThatThrownBy(() -> ai.requireCurrent(original)).hasFieldOrPropertyWithValue("errorCode",ErrorCode.AI_CONSENT_REQUIRED);
        assertThat(grant("trip",2).revision()).isEqualTo(3);
        assertThatThrownBy(() -> ai.requireCurrent(original)).hasFieldOrPropertyWithValue("errorCode",ErrorCode.AI_CONSENT_CHANGED);
        assertThatThrownBy(() -> grant("trip",0)).hasFieldOrPropertyWithValue("errorCode",ErrorCode.AI_CONSENT_CONFLICT);
    }
    @Test void jobBindingSurvivesReadsAndNeverMatchesARegrantedEpoch() {
        seed(); grant("trip",0);
        var permit = ai.open(userId,"trip");
        jobId = UUID.randomUUID();
        jobs.saveAndFlush(new RecommendJobEntity(jobId,null,"in_progress",null,null,null));
        jdbc.update("update user_service.recommend_jobs set owner_user_id=?,mode='init' where job_id=?",userId,jobId);
        assertThatThrownBy(() -> ai.requireJob(jobId.toString(),userId)).hasFieldOrPropertyWithValue("errorCode",ErrorCode.AI_CONSENT_CHANGED);
        ai.bindJob(jobId.toString(),permit);
        ai.requireJob(jobId.toString(),userId);
        ai.revoke(userId,"trip",1); grant("trip",2);
        assertThatThrownBy(() -> ai.requireJob(jobId.toString(),userId)).hasFieldOrPropertyWithValue("errorCode",ErrorCode.AI_CONSENT_CHANGED);
    }
    @Test void withdrawalRemainsAvailableAfterServicePolicyCorrectionAndDeletionCascades() {
        seed(); grant("vision",0);
        jdbc.update("update users set birth_date=? where id=?",java.sql.Date.valueOf("2012-01-01"),userId);
        assertThat(ai.revoke(userId,"vision",1).accepted()).isFalse();
        assertThatThrownBy(() -> grant("vision",2)).hasFieldOrPropertyWithValue("errorCode",ErrorCode.AGE_RESTRICTED);
        users.deleteById(userId);
        assertThat(records.findById(userId+":vision")).isEmpty();
        assertThatThrownBy(() -> ai.open(userId,"vision")).hasFieldOrPropertyWithValue("errorCode",ErrorCode.INVALID_TOKEN);
        userId=null;
    }
}

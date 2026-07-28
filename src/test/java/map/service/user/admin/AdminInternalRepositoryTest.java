package map.service.user.admin;

import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.recommend.RecommendJobEntity;
import map.service.user.recommend.RecommendJobRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AdminInternalRepositoryTest — 관리자 위임 조회용 신규 쿼리 통합 테스트 (H2)
 *
 * UserRepository 의 email/nickname 부분일치 검색과 RecommendJobRepository 의
 * 상태별 집계 쿼리를 user_service 스키마(H2 PostgreSQL 모드)에서 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("관리자 위임 조회 쿼리 통합 테스트 (H2)")
class AdminInternalRepositoryTest {

    @Autowired private UserRepository userRepository;
    @Autowired private RecommendJobRepository jobRepository;

    @Test
    @DisplayName("UserRepository — email/nickname 부분일치 검색(대소문자 무시)")
    void userSearch_containsIgnoreCase() {
        userRepository.save(User.builder()
                .email("alice@example.com").nickname("앨리스")
                .authProvider(AuthProvider.EMAIL).emailVerified(true).build());
        userRepository.save(User.builder()
                .email("bob@test.org").nickname("Bobby")
                .authProvider(AuthProvider.EMAIL).emailVerified(false).build());

        Page<User> byEmail = userRepository
                .findByEmailContainingIgnoreCaseOrNicknameContainingIgnoreCase(
                        "ALICE", "ALICE", PageRequest.of(0, 10));
        assertThat(byEmail.getTotalElements()).isEqualTo(1);
        assertThat(byEmail.getContent().get(0).getNickname()).isEqualTo("앨리스");

        Page<User> byNick = userRepository
                .findByEmailContainingIgnoreCaseOrNicknameContainingIgnoreCase(
                        "bobby", "bobby", PageRequest.of(0, 10));
        assertThat(byNick.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("RecommendJobRepository — 상태별 집계")
    void jobStats_groupByStatus() {
        jobRepository.save(new RecommendJobEntity(
                UUID.randomUUID(), null, "done", null, null, null));
        jobRepository.save(new RecommendJobEntity(
                UUID.randomUUID(), null, "done", null, null, null));
        jobRepository.save(new RecommendJobEntity(
                UUID.randomUUID(), null, "failed", null, "boom", null));

        List<Object[]> rows = jobRepository.countGroupByStatus();

        long done = rows.stream().filter(r -> "done".equals(r[0]))
                .mapToLong(r -> ((Number) r[1]).longValue()).sum();
        long failed = rows.stream().filter(r -> "failed".equals(r[0]))
                .mapToLong(r -> ((Number) r[1]).longValue()).sum();
        assertThat(done).isEqualTo(2);
        assertThat(failed).isEqualTo(1);

        assertThat(jobRepository.findByStatus("done", PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(2);
    }
}

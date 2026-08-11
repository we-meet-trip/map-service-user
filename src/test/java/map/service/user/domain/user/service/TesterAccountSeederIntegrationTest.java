package map.service.user.domain.user.service;

import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TesterAccountSeederIntegrationTest — 부팅 시더가 실제로 도는지 확인하는 통합 테스트
 *
 * 단위 테스트는 러너와 시더를 각각 검증할 뿐, 부팅 과정이 러너를 부르는지는 확인하지
 * 못한다. 러너 등록이 빠지면(예: ApplicationRunner 미구현) 단위 테스트는 전부 통과하는데
 * 실제 서버에서는 계정이 하나도 생기지 않는다. 이 테스트가 그 구간을 막는다.
 *
 * 켬/끔은 인라인 속성으로 지정한다 — 설정 파일이나 셸 환경변수보다 우선하므로 어느
 * 기기에서 돌려도 결과가 같다.
 */
@SpringBootTest
@ActiveProfiles("test")
// 시더는 트랜잭션을 커밋하므로, 공유 testdb 를 오염시키지 않도록 전용 인메모리 DB 를 쓴다.
// 실제 비밀번호는 여기 남기지 않는다 — 이 값은 테스트 전용이다.
@TestPropertySource(properties = {
        "tester.seed.enabled=true",
        "tester.seed.password=integration-test-pw!",
        "spring.datasource.url=jdbc:h2:mem:testerseeddb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
                + "DB_CLOSE_ON_EXIT=FALSE;INIT=CREATE SCHEMA IF NOT EXISTS user_service"
})
@DisplayName("테스터 계정 시더 통합 테스트")
class TesterAccountSeederIntegrationTest {

    private static final String RAW_PASSWORD = "integration-test-pw!";

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("부팅만으로 maptester1..5 가 로그인 가능한 상태로 준비된다")
    void seedsFiveAccountsOnStartup() {
        for (int index = 1; index <= 5; index++) {
            String email = "maptester%d@admin.map".formatted(index);
            Optional<User> found = userRepository.findByEmail(email);

            assertThat(found).as("계정 %s", email).isPresent();
            User user = found.orElseThrow();
            assertThat(user.getNickname()).isEqualTo("maptester%d".formatted(index));
            assertThat(user.getAuthProvider()).isEqualTo(AuthProvider.EMAIL);
            assertThat(user.isEmailVerified()).isTrue();
            assertThat(user.getBirthDate()).isNull();
            assertThat(user.getGender()).isNull();
            assertThat(user.getInterests()).isNull();
            assertThat(user.getThemes()).isNull();
            // 로그인은 이 대조로 통과 여부가 갈린다(AuthService.login 과 같은 방식).
            assertThat(passwordEncoder.matches(RAW_PASSWORD, user.getPasswordHash()))
                    .as("비밀번호 대조 %s", email)
                    .isTrue();
        }
    }
}

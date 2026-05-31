package map.service.user.domain.user.repository;

import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("UserRepository 통합 테스트 (H2 인메모리)")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("이메일로 사용자 조회 — 존재하는 경우 Optional<User> 반환")
    void findByEmail_existingUser_returnsUser() {
        User saved = userRepository.save(emailUser("find@example.com", "조회테스터"));

        Optional<User> found = userRepository.findByEmail("find@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
        assertThat(found.get().getNickname()).isEqualTo("조회테스터");
    }

    @Test
    @DisplayName("이메일로 사용자 조회 — 존재하지 않는 경우 Optional.empty() 반환")
    void findByEmail_nonExistingUser_returnsEmpty() {
        Optional<User> found = userRepository.findByEmail("none@example.com");

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("이메일 존재 여부 확인 — 존재하는 경우 true")
    void existsByEmail_existingUser_returnsTrue() {
        userRepository.save(emailUser("exists@example.com", "존재테스터"));

        assertThat(userRepository.existsByEmail("exists@example.com")).isTrue();
    }

    @Test
    @DisplayName("이메일 존재 여부 확인 — 존재하지 않는 경우 false")
    void existsByEmail_nonExistingUser_returnsFalse() {
        assertThat(userRepository.existsByEmail("nothere@example.com")).isFalse();
    }

    @Test
    @DisplayName("사용자 저장 — createdAt / updatedAt 자동 설정 확인")
    void save_newUser_setsTimestamps() {
        User saved = userRepository.save(emailUser("ts@example.com", "타임스탬프테스터"));

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("이메일 NULL인 Kakao 사용자 저장 — UNIQUE 제약 위반 없음")
    void save_kakaoUserWithNullEmail_noDuplicateViolation() {
        User kakao1 = userRepository.save(kakaoUser(null, "카카오유저1"));
        User kakao2 = userRepository.save(kakaoUser(null, "카카오유저2"));

        assertThat(kakao1.getId()).isNotEqualTo(kakao2.getId());
        assertThat(userRepository.count()).isGreaterThanOrEqualTo(2);
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private User emailUser(String email, String nickname) {
        return User.builder()
                .email(email)
                .nickname(nickname)
                .passwordHash("$2a$10$hashedpassword")
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(false)
                .build();
    }

    private User kakaoUser(String email, String nickname) {
        return User.builder()
                .email(email)
                .nickname(nickname)
                .authProvider(AuthProvider.KAKAO)
                .emailVerified(email != null)
                .build();
    }
}

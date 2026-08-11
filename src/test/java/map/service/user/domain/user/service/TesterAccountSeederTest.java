package map.service.user.domain.user.service;

import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TesterAccountSeederTest — 테스터 계정 한 건을 맞추는 규칙 단위 테스트
 *
 * 없으면 만들고, 있으면 비밀번호가 어긋났을 때만 고치며, 맞으면 아무 것도 쓰지 않는지
 * 확인한다. 닉네임이 보존되는지와 해시가 비어 있는 행을 다룰 수 있는지도 함께 본다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TesterAccountSeeder 단위 테스트")
class TesterAccountSeederTest {

    private static final String EMAIL = "maptester1@admin.map";
    private static final String NICKNAME = "maptester1";
    private static final String RAW_PASSWORD = "seeder-unit-pw!";
    private static final String ENCODED = "$2a$10$encoded-by-the-encoder-stub";

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private TesterAccountSeeder seeder;

    @Captor private ArgumentCaptor<User> savedUser;

    @Test
    @DisplayName("계정이 없으면 EMAIL 제공자·인증됨 상태로 만들고 취향 항목은 비워 둔다")
    void createsAccountWhenAbsent() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED);

        TesterAccountSeeder.Outcome outcome = seeder.ensureAccount(EMAIL, NICKNAME, RAW_PASSWORD);

        assertThat(outcome).isEqualTo(TesterAccountSeeder.Outcome.CREATED);
        verify(userRepository).save(savedUser.capture());
        User created = savedUser.getValue();
        assertThat(created.getEmail()).isEqualTo(EMAIL);
        assertThat(created.getNickname()).isEqualTo(NICKNAME);
        assertThat(created.getPasswordHash()).isEqualTo(ENCODED);
        assertThat(created.getAuthProvider()).isEqualTo(AuthProvider.EMAIL);
        assertThat(created.isEmailVerified()).isTrue();
        assertThat(created.getBirthDate()).isNull();
        assertThat(created.getGender()).isNull();
        assertThat(created.getInterests()).isNull();
        assertThat(created.getThemes()).isNull();
    }

    @Test
    @DisplayName("계정이 있고 비밀번호가 맞으면 아무 것도 쓰지 않는다")
    void leavesMatchingAccountUntouched() {
        User existing = existingUser("$2a$10$current", NICKNAME);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));
        when(passwordEncoder.matches(RAW_PASSWORD, "$2a$10$current")).thenReturn(true);

        TesterAccountSeeder.Outcome outcome = seeder.ensureAccount(EMAIL, NICKNAME, RAW_PASSWORD);

        assertThat(outcome).isEqualTo(TesterAccountSeeder.Outcome.UNCHANGED);
        assertThat(existing.getPasswordHash()).isEqualTo("$2a$10$current");
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    @DisplayName("비밀번호가 어긋나면 해시만 되돌리고 테스터가 고친 닉네임은 그대로 둔다")
    void repairsPasswordButKeepsNickname() {
        User existing = existingUser("$2a$10$stale", "테스터가 바꾼 이름");
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));
        when(passwordEncoder.matches(RAW_PASSWORD, "$2a$10$stale")).thenReturn(false);
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED);

        TesterAccountSeeder.Outcome outcome = seeder.ensureAccount(EMAIL, NICKNAME, RAW_PASSWORD);

        assertThat(outcome).isEqualTo(TesterAccountSeeder.Outcome.PASSWORD_REPAIRED);
        assertThat(existing.getPasswordHash()).isEqualTo(ENCODED);
        assertThat(existing.getNickname()).isEqualTo("테스터가 바꾼 이름");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("해시가 비어 있는 계정도 대조 없이 되돌린다")
    void repairsAccountWithoutHash() {
        User existing = existingUser(null, NICKNAME);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(ENCODED);

        TesterAccountSeeder.Outcome outcome = seeder.ensureAccount(EMAIL, NICKNAME, RAW_PASSWORD);

        assertThat(outcome).isEqualTo(TesterAccountSeeder.Outcome.PASSWORD_REPAIRED);
        assertThat(existing.getPasswordHash()).isEqualTo(ENCODED);
        // 해시가 없는 행을 대조에 넘기면 인코더가 예외를 던진다.
        verify(passwordEncoder, never()).matches(anyString(), any());
    }

    private static User existingUser(String passwordHash, String nickname) {
        return User.builder()
                .email(EMAIL)
                .nickname(nickname)
                .passwordHash(passwordHash)
                .authProvider(AuthProvider.EMAIL)
                .emailVerified(true)
                .build();
    }
}

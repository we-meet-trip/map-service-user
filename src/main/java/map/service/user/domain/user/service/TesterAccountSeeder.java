package map.service.user.domain.user.service;

import lombok.RequiredArgsConstructor;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * TesterAccountSeeder — 테스터 계정 한 건의 존재와 비밀번호를 보장한다.
 *
 * 팀원이 나눠 쓰는 계정이라 서버를 새로 띄울 때마다 같은 자격으로 들어갈 수 있어야
 * 한다. 어떤 계정을 몇 개 만들지는 부르는 쪽(TesterAccountSeedRunner)이 정하고,
 * 여기서는 계정 하나를 맞추는 일만 한다.
 *
 * 이미 있는 계정은 비밀번호가 어긋났을 때만 고친다. 매번 다시 인코딩하면 BCrypt 가
 * 일부러 느린 만큼 기동이 계정 수에 비례해 늘고, 솔트가 매번 달라져 아무 일도 없는
 * 행의 수정 시각이 계속 바뀐다.
 *
 * 닉네임과 나머지 프로필은 만들 때만 넣는다 — 닉네임은 내 정보 화면에서 바꿀 수 있는
 * 값이라, 부팅마다 덮어쓰면 테스터가 고쳐 둔 값이 조용히 되돌아간다.
 */
@Service
@RequiredArgsConstructor
public class TesterAccountSeeder {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /** 계정 하나를 맞춘 결과. 부르는 쪽이 요약 로그를 만드는 데 쓴다. */
    public enum Outcome {
        /** 없어서 새로 만들었다. */
        CREATED,
        /** 있었지만 비밀번호가 어긋나 되돌렸다. */
        PASSWORD_REPAIRED,
        /** 있었고 비밀번호도 맞아 아무 것도 쓰지 않았다. */
        UNCHANGED
    }

    /**
     * 이메일로 계정을 찾아 없으면 만들고, 있으면 비밀번호만 표준값에 맞춘다.
     *
     * 트랜잭션을 계정 단위로 끊는다. 한 트랜잭션에 여러 계정을 묶으면 제약 위반 하나가
     * 영속성 컨텍스트를 못 쓰는 상태로 만들어 나머지까지 함께 잃는다.
     */
    @Transactional
    public Outcome ensureAccount(String email, String nickname, String rawPassword) {
        Optional<User> found = userRepository.findByEmail(email);
        if (found.isEmpty()) {
            userRepository.save(User.builder()
                    .email(email)
                    .nickname(nickname)
                    .passwordHash(passwordEncoder.encode(rawPassword))
                    .authProvider(AuthProvider.EMAIL)
                    // 받을 수 없는 도메인이라 이 계정들은 어떤 인증 절차도 통과할 수
                    // 없다. 나중에 인증을 요구하는 관문이 생기면 false 는 이 계정들을
                    // 영구히 잠근다. 운영자가 직접 넣은 계정이므로 확인된 것으로 둔다.
                    .emailVerified(true)
                    .build());
            return Outcome.CREATED;
        }

        User user = found.get();
        if (matchesStandardPassword(user.getPasswordHash(), rawPassword)) {
            return Outcome.UNCHANGED;
        }
        user.resetPasswordHash(passwordEncoder.encode(rawPassword));
        return Outcome.PASSWORD_REPAIRED;
    }

    /**
     * 저장된 해시가 표준 비밀번호와 맞는지 본다.
     *
     * 해시가 비어 있는 경우를 먼저 걸러낸다 — 같은 이메일로 카카오 가입이 일어나면
     * 해시가 없는 행이 되고, 그대로 대조에 넘기면 예외가 난다.
     */
    private boolean matchesStandardPassword(String passwordHash, String rawPassword) {
        return passwordHash != null && passwordEncoder.matches(rawPassword, passwordHash);
    }
}

package map.service.user.global.config;

import lombok.extern.slf4j.Slf4j;
import map.service.user.domain.user.service.TesterAccountSeeder;
import map.service.user.domain.user.service.TesterAccountSeeder.Outcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * TesterAccountSeedRunner — 테스터 계정 5개를 부팅 때마다 보장한다.
 *
 * 팀원이 나눠 쓰는 계정을 사람이 회원가입으로 만들어 두면 데이터 볼륨을 지우는 순간
 * 함께 사라진다. 이 러너는 켜져 있는 동안 기동할 때마다 계정의 존재와 비밀번호를
 * 맞춰 두므로, 초기화한 환경도 다시 띄우기만 하면 같은 자격으로 들어갈 수 있다.
 *
 * 계정 수·이메일·닉네임은 상수다. 팀이 외우고 쓰는 값이라 환경마다 달라지면
 * "내 쪽에서만 안 된다"가 된다. 설정으로 여는 것은 켬/끔과 비밀번호뿐이다.
 *
 * ApplicationRunner 를 쓰는 이유는 실행 시점이다. 러너는 부팅 마무리 단계에서
 * '기동 완료'를 알리기 전에 실행되므로, 서버가 요청을 받기 시작한 뒤에도 계정이
 * 아직 없는 구간이 생기지 않는다. 스키마 준비 역시 앞서 끝난다 — 마이그레이션이
 * 끝나야 저장소 빈이 만들어지고, 러너는 그 빈을 받아야 만들어진다.
 *
 * 계정을 맞추는 일 자체는 TesterAccountSeeder 에 맡긴다. 트랜잭션은 프록시로 걸리기
 * 때문에 같은 객체 안에서 부르면 프록시를 지나치지 않아 트랜잭션 없이 실행되고,
 * 그러면 비밀번호 복구가 조용히 아무 일도 하지 않는 상태가 된다.
 */
@Slf4j
@Component
public class TesterAccountSeedRunner implements ApplicationRunner {

    private static final int ACCOUNT_COUNT = 5;
    private static final String EMAIL_FORMAT = "maptester%d@admin.map";
    private static final String NICKNAME_FORMAT = "maptester%d";

    private final TesterAccountSeeder seeder;
    private final boolean enabled;
    private final String rawPassword;

    public TesterAccountSeedRunner(
            TesterAccountSeeder seeder,
            @Value("${tester.seed.enabled:false}") boolean enabled,
            @Value("${tester.seed.password:}") String rawPassword
    ) {
        this.seeder = seeder;
        this.enabled = enabled;
        this.rawPassword = rawPassword;
    }

    /**
     * 부팅 시 계정 5개를 맞춘다. 꺼져 있으면 즉시 반환한다.
     *
     * 어떤 실패도 밖으로 내보내지 않는다. 러너에서 빠져나간 예외는 컨텍스트를 닫아
     * 서비스 기동을 막는데, 테스트 편의를 위한 기능이 서비스를 못 뜨게 해서는 안 된다.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            log.warn("tester seed skipped reason=password_not_set — TESTER_SEED_PASSWORD 를 채워야 한다");
            return;
        }

        log.warn("tester seed enabled count={} — 비밀번호가 알려진 계정이 생성된다", ACCOUNT_COUNT);

        int created = 0;
        int repaired = 0;
        int unchanged = 0;
        int failed = 0;

        for (int index = 1; index <= ACCOUNT_COUNT; index++) {
            String email = EMAIL_FORMAT.formatted(index);
            String nickname = NICKNAME_FORMAT.formatted(index);
            try {
                Outcome outcome = seeder.ensureAccount(email, nickname, rawPassword);
                switch (outcome) {
                    case CREATED -> created++;
                    case PASSWORD_REPAIRED -> repaired++;
                    case UNCHANGED -> unchanged++;
                }
                logOutcome(outcome, email, nickname);
            } catch (DataIntegrityViolationException e) {
                // 다른 인스턴스가 같은 계정을 먼저 만들었다는 뜻이다. 행이 존재한다는
                // 결과는 같으므로 실패로 세지 않는다. 저장은 커밋 시점에 반영되므로
                // 이 예외는 시더 안이 아니라 여기서 잡힌다.
                log.warn("tester seed conflict email={} reason={}", email, e.getMessage());
            } catch (Exception e) {
                // 한 계정이 막혀도 나머지는 계속 맞춘다.
                failed++;
                log.error("tester seed failed email={} reason={}", email, e.getMessage());
            }
        }

        log.info("tester seed summary created={} repaired={} unchanged={} failed={}",
                created, repaired, unchanged, failed);
    }

    private void logOutcome(Outcome outcome, String email, String nickname) {
        switch (outcome) {
            case CREATED -> log.info("tester seed created email={} nickname={}", email, nickname);
            case PASSWORD_REPAIRED -> log.info("tester seed password repaired email={}", email);
            case UNCHANGED -> log.debug("tester seed unchanged email={}", email);
        }
    }
}

package map.service.user.global.config;

import map.service.user.domain.user.service.TesterAccountSeeder;
import map.service.user.domain.user.service.TesterAccountSeeder.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TesterAccountSeedRunnerTest — 부팅 시더의 켬/끔과 실패 격리 단위 테스트
 *
 * 꺼져 있거나 비밀번호가 없으면 아무 것도 하지 않고, 켜져 있으면 정해진 5개 계정을
 * 정확한 이메일·닉네임으로 요청하며, 한 계정이 실패해도 나머지를 계속 처리하면서
 * 예외를 밖으로 내보내지 않는지 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TesterAccountSeedRunner 단위 테스트")
class TesterAccountSeedRunnerTest {

    private static final String PASSWORD = "runner-unit-pw!";

    @Mock private TesterAccountSeeder seeder;

    @Captor private ArgumentCaptor<String> emails;
    @Captor private ArgumentCaptor<String> nicknames;

    @Test
    @DisplayName("enabled=false → 시더를 부르지 않는다")
    void disabledIsNoOp() {
        TesterAccountSeedRunner runner = new TesterAccountSeedRunner(seeder, false, PASSWORD);

        runner.run(null);

        verifyNoInteractions(seeder);
    }

    @Test
    @DisplayName("enabled=true 지만 비밀번호가 비어 있으면 시더를 부르지 않는다")
    void blankPasswordIsNoOp() {
        TesterAccountSeedRunner runner = new TesterAccountSeedRunner(seeder, true, "   ");

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();

        verifyNoInteractions(seeder);
    }

    @Test
    @DisplayName("enabled=true → maptester1..5 를 정해진 이메일·닉네임으로 5회 요청한다")
    void seedsFiveAccounts() {
        when(seeder.ensureAccount(anyString(), anyString(), eq(PASSWORD)))
                .thenReturn(Outcome.CREATED);
        TesterAccountSeedRunner runner = new TesterAccountSeedRunner(seeder, true, PASSWORD);

        runner.run(null);

        verify(seeder, times(5))
                .ensureAccount(emails.capture(), nicknames.capture(), eq(PASSWORD));
        assertThat(emails.getAllValues()).containsExactly(
                "maptester1@admin.map", "maptester2@admin.map", "maptester3@admin.map",
                "maptester4@admin.map", "maptester5@admin.map");
        assertThat(nicknames.getAllValues()).containsExactly(
                "maptester1", "maptester2", "maptester3", "maptester4", "maptester5");
    }

    @Test
    @DisplayName("한 계정이 실패해도 나머지를 계속 처리하고 예외를 밖으로 내보내지 않는다")
    void keepsGoingAfterFailure() {
        when(seeder.ensureAccount(anyString(), anyString(), eq(PASSWORD)))
                .thenReturn(Outcome.CREATED);
        when(seeder.ensureAccount(eq("maptester2@admin.map"), anyString(), eq(PASSWORD)))
                .thenThrow(new IllegalStateException("boom"));
        TesterAccountSeedRunner runner = new TesterAccountSeedRunner(seeder, true, PASSWORD);

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();

        verify(seeder, times(5)).ensureAccount(anyString(), anyString(), eq(PASSWORD));
    }

    @Test
    @DisplayName("동시 기동으로 같은 계정이 겹쳐도 기동을 막지 않는다")
    void tolerantToConcurrentSeeding() {
        when(seeder.ensureAccount(anyString(), anyString(), eq(PASSWORD)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        TesterAccountSeedRunner runner = new TesterAccountSeedRunner(seeder, true, PASSWORD);

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();

        verify(seeder, times(5)).ensureAccount(anyString(), anyString(), eq(PASSWORD));
    }
}

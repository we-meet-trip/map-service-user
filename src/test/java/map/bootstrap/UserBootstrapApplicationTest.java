package map.bootstrap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class UserBootstrapApplicationTest {
    private Map<String, String> environment() {
        return new HashMap<>(Map.of("USER_BOOTSTRAP_URL", "jdbc:postgresql://127.0.0.1:1/synthetic",
                "USER_BOOTSTRAP_EXPECTED_DATABASE", "synthetic", "USER_BOOTSTRAP_USERNAME", "map_user_bootstrap",
                "USER_BOOTSTRAP_PASSWORD", "synthetic-password", "USER_BOOTSTRAP_MARKER", "a".repeat(64)));
    }

    @Test void checkConfigNeverConnectsAndOriginalSqlIsPinned() {
        var calls = new AtomicInteger();
        var output = new ArrayList<String>();
        assertThat(UserBootstrapApplication.run(new String[]{"check-config"}, environment(), config -> calls.incrementAndGet(), output::add)).isZero();
        assertThat(calls).hasValue(0);
        assertThat(output).containsExactly("{\"status\":\"configuration_valid\",\"database_connected\":false}");
        UserBootstrapApplication.verifyResources();
    }

    @Test void refusesUnknownEnvironmentAndSpringAliasesBeforeConnection() {
        for (String key : new String[]{"SPRING_DATASOURCE_URL", "spring.datasource.url", "SPRING.DATASOURCE.URL",
                "spring_datasource_url", "REDIS_HOST", "GEMINI_API_KEY", "USER_MIGRATION_PASSWORD", "JAVA_TOOL_OPTIONS",
                "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "USER_BOOTSTRAP_TARGET", "AWS_ACCESS_KEY_ID", "USER_ADMIN_INTERNAL_TOKEN"}) {
            var env = environment(); env.put(key, "do-not-echo");
            assertThat(UserBootstrapApplication.run(new String[]{"bootstrap"}, env,
                    ignored -> { throw new AssertionError("must not connect"); }, ignored -> {})).isEqualTo(2);
        }
    }

    @Test void cannotSelectNormalMigrationOrRepairOrAlternateTarget() {
        for (String operation : new String[]{"migrate", "validate", "repair", "baseline", "clean", "4"})
            assertThatThrownBy(() -> UserBootstrapApplication.Configuration.parse(new String[]{operation}, environment())).isInstanceOf(IllegalStateException.class);
        var env = environment(); env.put("USER_BOOTSTRAP_EXPECTED_DATABASE", "wrong");
        assertThatThrownBy(() -> UserBootstrapApplication.Configuration.parse(new String[]{"bootstrap"}, env)).hasMessage("bootstrap_target_mismatch");
        env.put("USER_BOOTSTRAP_EXPECTED_DATABASE", "synthetic"); env.put("USER_BOOTSTRAP_MARKER", "unsafe' marker");
        assertThatThrownBy(() -> UserBootstrapApplication.Configuration.parse(new String[]{"bootstrap"}, env)).hasMessage("bootstrap_marker_invalid");
    }

    @Test void flywayHasOnlyFixedGenuineLegacyTargetAndSafeOptions() {
        var config = UserBootstrapApplication.flywayConfiguration(UserBootstrapApplication.Configuration.parse(new String[]{"bootstrap"}, environment()));
        assertThat(config.getTarget().toString()).isEqualTo("4");
        assertThat(config.isCleanDisabled()).isTrue();
        assertThat(config.isBaselineOnMigrate()).isFalse();
        assertThat(config.isCreateSchemas()).isFalse();
        assertThat(config.isOutOfOrder()).isFalse();
        assertThat(config.getConnectRetries()).isZero();
        assertThat(config.getLockRetryCount()).isZero();
        assertThat(config.getInitSql()).doesNotContain("SET ROLE");
    }

    @Test void failuresAreRedactedAndRequireOperatorQuarantine() {
        var output = new ArrayList<String>();
        assertThat(UserBootstrapApplication.run(new String[]{"bootstrap"}, environment(),
                ignored -> { throw new IllegalStateException("password jdbc:postgresql://secret SQL private"); }, output::add)).isEqualTo(1);
        assertThat(output).containsExactly("{\"status\":\"bootstrap_failed\",\"operator_quarantine_required\":true}");
    }
}

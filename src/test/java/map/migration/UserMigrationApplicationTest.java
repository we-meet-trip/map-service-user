package map.migration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class UserMigrationApplicationTest {
    static Map<String, String> safeEnvironment() {
        return Map.of("USER_MIGRATION_URL", "jdbc:postgresql://127.0.0.1:1/synthetic?currentSchema=user_service",
                "USER_MIGRATION_USERNAME", "map_user_migrator", "USER_MIGRATION_PASSWORD", "unit-only-never-print");
    }

    @Test
    void configurationCheckNeverCallsDatabaseOrServingCode() {
        var calls = new AtomicInteger();
        var output = new ArrayList<String>();
        int exit = UserMigrationApplication.run(new String[]{"check-config"}, safeEnvironment(), config -> calls.incrementAndGet(), output::add);
        assertThat(exit).isZero();
        assertThat(calls).hasValue(0);
        assertThat(output).containsExactly("{\"status\":\"configuration_valid\",\"database_connected\":false}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SPRING_DATASOURCE_URL", "REDIS_PASSWORD", "POSTGRES_PASSWORD", "JWT_PRIVATE_KEY",
            "USER_DATABASE_PASSWORD", "LOCATION_CRYPTO_KEYS", "TRAINING_EXPORT_ENABLED", "TESTER_SEED_ENABLED",
            "HUB_BASE_URL", "GEMINI_API_KEY", "USER_MIGRATION_OPTIONS", "JAVA_TOOL_OPTIONS", "USER_ADMIN_INTERNAL_TOKEN"})
    void servingSecretsOrFlagsRejectBeforeAnyDatabaseCall(String name) {
        var environment = new HashMap<>(safeEnvironment());
        environment.put(name, "synthetic-value-must-not-appear");
        var output = new ArrayList<String>();
        int exit = UserMigrationApplication.run(new String[]{"migrate"}, environment, config -> {
            throw new AssertionError("source must not connect");
        }, output::add);
        assertThat(exit).isEqualTo(2);
        assertThat(output).containsExactly("{\"status\":\"configuration_rejected\"}");
    }

    @Test
    void forbidsCleanupRepairBaselineAndOverrides() {
        for (String operation : new String[]{"clean", "repair", "baseline", "undo", "migrate --url=other"}) {
            assertThat(UserMigrationApplication.run(new String[]{operation}, safeEnvironment(), config -> {
                throw new AssertionError("invalid operation reached database");
            }, ignored -> {})).isEqualTo(2);
        }
        var shared = new HashMap<>(safeEnvironment());
        shared.put("USER_MIGRATION_USERNAME", "map");
        assertThatThrownBy(() -> UserMigrationApplication.Configuration.parse(new String[]{"migrate"}, shared))
                .hasMessage("migration_role_required");
    }

    @Test
    void failedDatabaseOrMigrationDoesNotLeakExceptionCauseOrRetry() {
        var calls = new AtomicInteger();
        var output = new ArrayList<String>();
        assertThat(UserMigrationApplication.run(new String[]{"migrate"}, safeEnvironment(), config -> {
            calls.incrementAndGet();
            throw new IllegalStateException("jdbc:private password=unit-only-never-print SQL payload");
        }, output::add)).isOne();
        assertThat(calls).hasValue(1);
        assertThat(output).containsExactly("{\"status\":\"migration_failed\"}");
    }

    @Test
    void onlyBoundedPrivilegeFailureCodesReachOutput() {
        var output = new ArrayList<String>();
        assertThat(UserMigrationApplication.run(new String[]{"migrate"}, safeEnvironment(), config -> {
            throw new IllegalStateException("database_privilege_migration_membership");
        }, output::add)).isOne();
        assertThat(output).containsExactly("{\"status\":\"migration_failed\",\"code\":\"database_privilege_migration_membership\"}");
        output.clear();
        UserMigrationApplication.run(new String[]{"migrate"}, safeEnvironment(), config -> {
            throw new IllegalStateException("database_privilege_invalid password=synthetic-never-print");
        }, output::add);
        assertThat(output).containsExactly("{\"status\":\"migration_failed\"}");
    }

    @Test
    void directFlywayConfigurationCannotCleanBaselineOrIgnoreFutureMigrations() {
        var config = UserMigrationApplication.flywayConfiguration(UserMigrationApplication.Configuration.parse(new String[]{"migrate"}, safeEnvironment()));
        assertThat(config.isCleanDisabled()).isTrue();
        assertThat(config.isBaselineOnMigrate()).isFalse();
        assertThat(config.isOutOfOrder()).isFalse();
        assertThat(config.isCreateSchemas()).isFalse();
        assertThat(config.isValidateOnMigrate()).isTrue();
        assertThat(config.getIgnoreMigrationPatterns()).isEmpty();
        assertThat(config.getLoggers()).isEmpty();
        assertThat(config.getConnectRetries()).isZero();
        assertThat(config.getSchemas()).containsExactly("user_service");
        assertThat(config.getInitSql()).isEqualTo("SET ROLE map_user_owner");
    }
}

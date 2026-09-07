package map.migration;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import map.database.UserDatabaseContract;
import map.database.UserDatabasePrivileges;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import static map.database.UserDatabaseContract.*;

/** Plain Java entrypoint. Deliberately outside Spring's serving/export component scans. */
public final class UserMigrationApplication {
    private UserMigrationApplication() {}

    static final class Configuration {
        final String operation;
        final String url;
        final String password;

        private Configuration(String operation, String url, String password) {
            this.operation = operation;
            this.url = url;
            this.password = password;
        }

        // Do not add a generated toString(): configuration contains a credential.
        static Configuration parse(String[] args, Map<String, String> environment) {
            require(args.length == 1 && Set.of("check-config", "validate", "migrate").contains(args[0]), "migration_operation_invalid");
            Set<String> allowed = Set.of("USER_MIGRATION_URL", "USER_MIGRATION_USERNAME", "USER_MIGRATION_PASSWORD");
            for (String key : environment.keySet()) {
                String name = key.toUpperCase(java.util.Locale.ROOT);
                boolean sensitive = name.startsWith("SPRING_") || name.startsWith("POSTGRES_")
                        || name.startsWith("USER_DATABASE_") || name.startsWith("USER_MIGRATION_")
                        || name.startsWith("REDIS_") || name.startsWith("JWT_") || name.startsWith("LOCATION_")
                        || name.startsWith("TRAINING_") || name.startsWith("TESTER_") || name.startsWith("HUB_")
                        || name.startsWith("AGENT_") || name.startsWith("YOLO_") || name.startsWith("KAKAO_")
                        || name.startsWith("APPLE_") || name.startsWith("GOOGLE_") || name.startsWith("GEMINI_")
                        || name.endsWith("_TOKEN") || name.endsWith("_SECRET") || name.endsWith("_KEY")
                        || name.endsWith("_PASSWORD") || name.equals("SPRING_APPLICATION_JSON")
                        || name.equals("JAVA_TOOL_OPTIONS") || name.equals("JDK_JAVA_OPTIONS") || name.equals("_JAVA_OPTIONS");
                require(!sensitive || allowed.contains(key), "migration_environment_forbidden");
            }
            String url = environment.get("USER_MIGRATION_URL");
            validateUrl(url);
            require(MIGRATOR.equals(environment.get("USER_MIGRATION_USERNAME")), "migration_role_required");
            String password = environment.get("USER_MIGRATION_PASSWORD");
            require(password != null && !password.isBlank(), "migration_password_required");
            return new Configuration(args[0], url, password);
        }
    }

    static FluentConfiguration flywayConfiguration(Configuration config) {
        return Flyway.configure().dataSource(config.url, MIGRATOR, config.password)
                .jdbcProperties(Map.of("connectTimeout", "5", "socketTimeout", "300", "ApplicationName", "map-user-migrator"))
                .schemas(SCHEMA).defaultSchema(SCHEMA).locations("classpath:db/migration")
                .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).outOfOrder(false)
                .validateOnMigrate(true).ignoreMigrationPatterns(new String[0])
                .connectRetries(0).lockRetryCount(30).initSql("SET ROLE map_user_owner")
                // Error messages may contain SQL/values. Emit only our bounded status codes below.
                .loggers(new String[0]);
    }

    private static int execute(Configuration config) {
        try (var connection = UserDatabaseContract.connect(config.url, MIGRATOR, config.password)) {
            UserDatabasePrivileges.verifyMigrator(connection);
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException("migration_database_verification_failed");
        }
        Flyway flyway = flywayConfiguration(config).load();
        if (config.operation.equals("validate")) {
            flyway.validate();
            return 0;
        }
        int count = flyway.migrate().migrationsExecuted;
        // Default DML grants cover newly created application tables; history is metadata only.
        // If finalization fails, exit nonzero and serving's privilege guard prevents startup.
        try (var connection = UserDatabaseContract.connect(config.url, MIGRATOR, config.password)) {
            UserDatabasePrivileges.verifyMigrator(connection);
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                statement.execute("REVOKE ALL ON TABLE user_service.flyway_schema_history FROM map_user_runtime");
                statement.execute("GRANT SELECT ON TABLE user_service.flyway_schema_history TO map_user_runtime");
            }
            connection.commit();
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException("migration_history_grant_failed");
        }
        return count;
    }

    static int run(String[] args, Map<String, String> environment, ToIntFunction<Configuration> executor,
                   Consumer<String> output) {
        final Configuration config;
        try {
            config = Configuration.parse(args, environment);
        } catch (RuntimeException error) {
            output.accept("{\"status\":\"configuration_rejected\"}");
            return 2;
        }
        if (config.operation.equals("check-config")) {
            output.accept("{\"status\":\"configuration_valid\",\"database_connected\":false}");
            return 0;
        }
        try {
            int count = executor.applyAsInt(config);
            output.accept("{\"status\":\"complete\",\"operation\":\"" + config.operation
                    + "\",\"migrations_executed\":" + count + "}");
            return 0;
        } catch (RuntimeException error) {
            output.accept("{\"status\":\"migration_failed\"}");
            return 1;
        }
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), UserMigrationApplication::execute, System.out::println));
    }
}

package map.bootstrap;

import java.net.URI;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import map.database.UserDatabaseContract;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import static map.database.UserDatabaseContract.require;

/** One-use empty-host entrypoint. No Spring context, serving beans, or role administration. */
public final class UserBootstrapApplication {
    static final String ROLE = "map_user_bootstrap";
    static final String MARKER_PREFIX = "map-user-bootstrap:v1:";
    static final Map<String, String> SQL_DIGESTS = Map.of(
            "V001__init.sql", "da6de2b8361ca1ea0b162fb146896bca8454f60d8c82e71b549aaca61eb3d52c",
            "V002__schedules.sql", "5a9dfafa11f3159f56e92eb58e2992003131f099e08cba3f3114242b23362a78",
            "V003__schedules_date_check.sql", "4fd704afa97d93ca337d16e867782869a3064306b133f14a1a796816bfca360f",
            "V004__init_user_service.sql", "0121bf9fd3ead8a6510c338abc03708e927434a8b42e15b0073e8d5fb9ecbc12");
    private UserBootstrapApplication() {}

    static final class Configuration {
        final String operation, url, password, database, marker;
        private Configuration(String operation, String url, String password, String database, String marker) {
            this.operation = operation; this.url = url; this.password = password;
            this.database = database; this.marker = marker;
        }
        // No toString: contains a credential and an independent one-use target marker.
        static Configuration parse(String[] args, Map<String, String> environment) {
            require(args.length == 1 && Set.of("check-config", "bootstrap").contains(args[0]), "bootstrap_operation_invalid");
            Set<String> allowed = Set.of("USER_BOOTSTRAP_URL", "USER_BOOTSTRAP_USERNAME", "USER_BOOTSTRAP_PASSWORD",
                    "USER_BOOTSTRAP_EXPECTED_DATABASE", "USER_BOOTSTRAP_MARKER");
            // An allowlist also rejects dotted/lowercase Spring aliases and every serving/provider secret.
            Set<String> process = Set.of("PATH", "JAVA_HOME", "HOME", "LANG", "LC_ALL", "TZ", "TMPDIR");
            for (String key : environment.keySet()) require(allowed.contains(key) || process.contains(key), "bootstrap_environment_forbidden");
            String url = environment.get("USER_BOOTSTRAP_URL");
            UserDatabaseContract.validateUrl(url);
            String database = environment.get("USER_BOOTSTRAP_EXPECTED_DATABASE");
            require(database != null && database.matches("[a-z][a-z0-9_]{0,62}")
                    && !Set.of("postgres", "template0", "template1").contains(database), "bootstrap_database_invalid");
            require(URI.create(url.substring(5)).getPath().equals("/" + database), "bootstrap_target_mismatch");
            require(ROLE.equals(environment.get("USER_BOOTSTRAP_USERNAME")), "bootstrap_role_required");
            String password = environment.get("USER_BOOTSTRAP_PASSWORD");
            require(password != null && !password.isBlank(), "bootstrap_password_required");
            String marker = environment.get("USER_BOOTSTRAP_MARKER");
            require(marker != null && marker.matches("[0-9a-f]{64}"), "bootstrap_marker_invalid");
            return new Configuration(args[0], url, password, database, marker);
        }
    }

    static void verifyResources() {
        for (var item : SQL_DIGESTS.entrySet()) {
            try (var input = UserBootstrapApplication.class.getResourceAsStream("/db/migration/" + item.getKey())) {
                require(input != null, "bootstrap_resource_missing");
                require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))
                        .equals(item.getValue()), "bootstrap_resource_mismatch");
            } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) {
                throw new IllegalStateException("bootstrap_resource_unreadable");
            }
        }
    }

    static FluentConfiguration flywayConfiguration(Configuration config) {
        return Flyway.configure().dataSource(config.url, ROLE, config.password)
                .jdbcProperties(Map.of("connectTimeout", "5", "socketTimeout", "60", "ApplicationName", "map-user-bootstrap"))
                .schemas("user_service").defaultSchema("user_service").locations("classpath:db/migration")
                .target("4").createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).outOfOrder(false)
                .validateOnMigrate(true).ignoreMigrationPatterns(new String[0]).connectRetries(0).lockRetryCount(0)
                .initSql("SET statement_timeout='30s'; SET lock_timeout='5s'").loggers(new String[0]);
    }

    static Map<String, String> emptyChecks() {
        var checks = new LinkedHashMap<String, String>();
        checks.put("postgres_version", "SELECT current_setting('server_version_num')::integer>=170000");
        checks.put("identity", "SELECT current_user='map_user_bootstrap' AND session_user=current_user");
        checks.put("role", "SELECT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit AND rolvaliduntil>now() AND rolvaliduntil<=now()+interval '1 hour' FROM pg_roles WHERE rolname=current_user");
        checks.put("membership", "SELECT NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user) OR roleid=(SELECT oid FROM pg_roles WHERE rolname=current_user))");
        checks.put("database_owner", "SELECT datdba<>(SELECT oid FROM pg_roles WHERE rolname=current_user) AND NOT datistemplate FROM pg_database WHERE datname=current_database()");
        checks.put("database_privileges", "SELECT has_database_privilege(current_database(),'CONNECT') AND has_database_privilege(current_database(),'CREATE') AND NOT has_database_privilege(current_database(),'TEMP')");
        checks.put("schemas", "SELECT NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname NOT IN ('user_service','public','information_schema') AND nspname NOT LIKE 'pg\\_%' ESCAPE '\\')");
        checks.put("schema_owner", "SELECT pg_get_userbyid(nspowner)=current_user FROM pg_namespace WHERE nspname='user_service'");
        checks.put("foreign_schema_create", "SELECT NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname<>'user_service' AND has_schema_privilege(oid,'CREATE'))");
        String ns = "n.nspname<>'information_schema' AND n.nspname NOT LIKE 'pg\\_%' ESCAPE '\\'";
        checks.put("relations", "SELECT NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE " + ns + ")");
        checks.put("routines", "SELECT NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE " + ns + ")");
        checks.put("types", "SELECT NOT EXISTS (SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE " + ns + ")");
        checks.put("extensions", "SELECT NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname<>'plpgsql')");
        checks.put("auxiliary_objects", "SELECT NOT EXISTS (SELECT 1 FROM pg_foreign_server) AND NOT EXISTS (SELECT 1 FROM pg_event_trigger) AND NOT EXISTS (SELECT 1 FROM pg_largeobject_metadata) AND NOT EXISTS (SELECT 1 FROM pg_publication) AND NOT EXISTS (SELECT 1 FROM pg_default_acl)");
        return checks;
    }

    private static void scalar(Connection connection, String sql, String code, String... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int i = 0; i < values.length; i++) statement.setString(i + 1, values[i]);
            try (var rows = statement.executeQuery()) {
                require(rows.next() && rows.getBoolean(1) && !rows.wasNull() && !rows.next(), code);
            }
        }
    }

    private static int execute(Configuration config) {
        verifyResources();
        try (var connection = UserDatabaseContract.connect(config.url, ROLE, config.password)) {
            scalar(connection, "SELECT pg_try_advisory_lock(736281904, 104)", "bootstrap_already_running");
            scalar(connection, "SELECT current_database()=? AND shobj_description(oid,'pg_database')=? FROM pg_database WHERE datname=current_database()",
                    "bootstrap_target_marker_mismatch", config.database, MARKER_PREFIX + config.marker);
            scalar(connection, "SELECT obj_description(oid,'pg_namespace')=? FROM pg_namespace WHERE nspname='user_service'",
                    "bootstrap_attempt_not_ready", MARKER_PREFIX + config.marker + ":ready");
            for (var check : emptyChecks().entrySet()) scalar(connection, check.getValue(), "bootstrap_guard_" + check.getKey());
            // Durable before any Flyway operation. A crash/timeout cannot silently restart even with zero history.
            // Marker is constrained hex, so no arbitrary SQL can enter this comment.
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                statement.execute("COMMENT ON SCHEMA user_service IS '" + MARKER_PREFIX + config.marker + ":started'");
            }
            int count = flywayConfiguration(config).load().migrate().migrationsExecuted;
            require(count == 4, "bootstrap_migration_count");
            scalar(connection, "SELECT count(*)=4 AND bool_and(success AND type='SQL' AND installed_by='map_user_bootstrap') AND array_agg(version ORDER BY installed_rank)=ARRAY['001','002','003','004']::varchar[] AND array_agg(checksum ORDER BY installed_rank)=ARRAY[-192854188,-2113231432,1973132559,-777713445] FROM user_service.flyway_schema_history", "bootstrap_history_mismatch");
            return count;
        } catch (SQLException error) {
            throw new IllegalStateException("bootstrap_database_failed");
        }
    }

    static int run(String[] args, Map<String, String> environment, ToIntFunction<Configuration> executor, Consumer<String> output) {
        final Configuration config;
        try { config = Configuration.parse(args, environment); verifyResources(); }
        catch (RuntimeException error) { output.accept("{\"status\":\"configuration_rejected\"}"); return 2; }
        if (config.operation.equals("check-config")) {
            output.accept("{\"status\":\"configuration_valid\",\"database_connected\":false}"); return 0;
        }
        try {
            int count = executor.applyAsInt(config);
            output.accept("{\"status\":\"bootstrap_complete\",\"migrations_executed\":" + count + ",\"operator_finalization_required\":true}"); return 0;
        } catch (RuntimeException error) {
            // Only fixed internal codes are public. JDBC/Flyway SQL, URLs, credentials and marker remain private.
            String code = error.getMessage();
            String detail = code != null && code.matches("bootstrap_(?:guard_[a-z_]+|already_running|target_marker_mismatch|attempt_not_ready|migration_count|history_mismatch|database_failed|resource_missing|resource_mismatch|resource_unreadable)")
                    ? ",\"code\":\"" + code + "\"" : "";
            output.accept("{\"status\":\"bootstrap_failed\"" + detail + ",\"operator_quarantine_required\":true}"); return 1;
        }
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), UserBootstrapApplication::execute, System.out::println));
    }
}

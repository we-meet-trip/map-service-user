package map.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import static map.database.UserDatabaseContract.*;

/** Checks effective privileges (including PUBLIC), never user rows. Every query is read-only. */
public final class UserDatabasePrivileges {
    private UserDatabasePrivileges() {}

    static Map<String, String> runtimeChecks() {
        Map<String, String> checks = commonChecks(RUNTIME);
        checks.put("runtime_membership", "SELECT NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user))");
        checks.put("database_create_or_temp", "SELECT NOT has_database_privilege(current_database(), 'CREATE') AND NOT has_database_privilege(current_database(), 'TEMP')");
        checks.put("schema_create", "SELECT NOT EXISTS (SELECT 1 FROM pg_namespace WHERE has_schema_privilege(oid, 'CREATE'))");
        checks.putAll(crossSchemaChecks());
        checks.put("schema_usage", "SELECT has_schema_privilege('user_service', 'USAGE')");
        checks.put("relation_ownership", "SELECT NOT EXISTS (SELECT 1 FROM pg_class WHERE relowner=(SELECT oid FROM pg_roles WHERE rolname=current_user))");
        checks.put("type_or_routine_ownership", "SELECT NOT EXISTS (SELECT 1 FROM pg_type WHERE typowner=(SELECT oid FROM pg_roles WHERE rolname=current_user)) AND NOT EXISTS (SELECT 1 FROM pg_proc WHERE proowner=(SELECT oid FROM pg_roles WHERE rolname=current_user))");
        checks.put("schema_owner", "SELECT pg_get_userbyid(nspowner)='map_user_owner' FROM pg_namespace WHERE nspname='user_service'");
        checks.put("owned_relations", "SELECT NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='user_service' AND c.relkind IN ('r','p','v','m','S','f') AND pg_get_userbyid(c.relowner)<>'map_user_owner')");
        checks.put("table_dml", "SELECT NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='user_service' AND c.relkind IN ('r','p') AND c.relname<>'flyway_schema_history' AND NOT (has_table_privilege(c.oid,'SELECT') AND has_table_privilege(c.oid,'INSERT') AND has_table_privilege(c.oid,'UPDATE') AND has_table_privilege(c.oid,'DELETE')))");
        checks.put("table_excess_privileges", "SELECT NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='user_service' AND c.relkind IN ('r','p','v','m','f') AND (has_table_privilege(c.oid,'TRUNCATE') OR has_table_privilege(c.oid,'REFERENCES') OR has_table_privilege(c.oid,'TRIGGER') OR has_table_privilege(c.oid,'MAINTAIN')))");
        checks.put("table_grant_options", "SELECT NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='user_service' AND c.relkind IN ('r','p','v','m','f') AND (has_table_privilege(c.oid,'SELECT WITH GRANT OPTION') OR has_table_privilege(c.oid,'INSERT WITH GRANT OPTION') OR has_table_privilege(c.oid,'UPDATE WITH GRANT OPTION') OR has_table_privilege(c.oid,'DELETE WITH GRANT OPTION') OR has_any_column_privilege(c.oid,'SELECT WITH GRANT OPTION') OR has_any_column_privilege(c.oid,'INSERT WITH GRANT OPTION') OR has_any_column_privilege(c.oid,'UPDATE WITH GRANT OPTION'))) ");
        checks.put("history_read_only", "SELECT has_table_privilege('user_service.flyway_schema_history','SELECT') AND NOT (has_table_privilege('user_service.flyway_schema_history','INSERT') OR has_table_privilege('user_service.flyway_schema_history','UPDATE') OR has_table_privilege('user_service.flyway_schema_history','DELETE'))");
        checks.put("sequence_usage", "SELECT NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='user_service' AND c.relkind='S' AND (NOT has_sequence_privilege(c.oid,'USAGE') OR NOT has_sequence_privilege(c.oid,'SELECT') OR has_sequence_privilege(c.oid,'UPDATE') OR has_sequence_privilege(c.oid,'SELECT WITH GRANT OPTION') OR has_sequence_privilege(c.oid,'USAGE WITH GRANT OPTION')))");
        return checks;
    }

    private static Map<String, String> crossSchemaChecks() {
        Map<String, String> checks = new LinkedHashMap<>();
        // USAGE alone is not data access. PUBLIC grants and column-only grants also count.
        // PostGIS's spatial_ref_sys is shared reference metadata, not a service-owned table.
        String reference = "(n.nspname='public' AND c.relname='spatial_ref_sys' AND EXISTS (SELECT 1 FROM pg_depend d JOIN pg_extension e ON e.oid=d.refobjid WHERE d.classid='pg_class'::regclass AND d.objid=c.oid AND d.refclassid='pg_extension'::regclass AND d.deptype='e' AND e.extname='postgis'))";
        checks.put("cross_schema_data", "SELECT NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname<>'user_service' AND n.nspname<>'information_schema' AND n.nspname NOT LIKE 'pg_%' AND c.relkind IN ('r','p','v','m','f') AND ((NOT " + reference + " AND (has_table_privilege(c.oid,'SELECT') OR has_any_column_privilege(c.oid,'SELECT'))) OR has_table_privilege(c.oid,'INSERT') OR has_table_privilege(c.oid,'UPDATE') OR has_table_privilege(c.oid,'DELETE') OR has_table_privilege(c.oid,'TRUNCATE') OR has_table_privilege(c.oid,'REFERENCES') OR has_table_privilege(c.oid,'TRIGGER') OR has_table_privilege(c.oid,'MAINTAIN') OR has_any_column_privilege(c.oid,'INSERT') OR has_any_column_privilege(c.oid,'UPDATE') OR has_any_column_privilege(c.oid,'REFERENCES')))");
        checks.put("cross_schema_sequence", "SELECT NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname<>'user_service' AND n.nspname<>'information_schema' AND n.nspname NOT LIKE 'pg_%' AND c.relkind='S' AND (has_sequence_privilege(c.oid,'USAGE') OR has_sequence_privilege(c.oid,'SELECT') OR has_sequence_privilege(c.oid,'UPDATE') OR has_sequence_privilege(c.oid,'SELECT WITH GRANT OPTION') OR has_sequence_privilege(c.oid,'USAGE WITH GRANT OPTION')))");
        checks.put("security_definer", "SELECT NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname<>'information_schema' AND n.nspname NOT LIKE 'pg_%' AND p.prosecdef AND has_function_privilege(p.oid,'EXECUTE'))");
        return checks;
    }

    private static Map<String, String> commonChecks(String expected) {
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("login_identity", "SELECT current_user='" + expected + "' AND session_user=current_user");
        checks.put("role_flags", "SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname=current_user");
        return checks;
    }

    static Map<String, String> migratorChecks() {
        Map<String, String> checks = commonChecks(MIGRATOR);
        checks.put("migration_owner_flags", "SELECT NOT rolcanlogin AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls AND NOT rolinherit FROM pg_roles WHERE rolname='map_user_owner'");
        checks.put("migration_membership", "SELECT COUNT(*)=1 AND bool_and(pg_get_userbyid(roleid)='map_user_owner' AND NOT admin_option AND NOT inherit_option AND set_option) FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname=current_user)");
        checks.put("owner_membership", "SELECT NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member=(SELECT oid FROM pg_roles WHERE rolname='map_user_owner'))");
        checks.put("schema_owner", "SELECT pg_get_userbyid(nspowner)='map_user_owner' FROM pg_namespace WHERE nspname='user_service'");
        // V004's CREATE SCHEMA IF NOT EXISTS still needs database CREATE, even for an existing schema.
        // Bootstrap is a separate reviewed step, never an implicit privilege escalation here.
        checks.put("legacy_bootstrap_history_required", "SELECT to_regclass('user_service.flyway_schema_history') IS NOT NULL");
        checks.put("legacy_bootstrap_v004_required", "SELECT EXISTS (SELECT 1 FROM user_service.flyway_schema_history WHERE success AND version ~ '^0*4$')");
        return checks;
    }

    public static void verifyRuntime(Connection connection) throws SQLException {
        check(connection, runtimeChecks());
    }

    public static void verifyMigrator(Connection connection) throws SQLException {
        check(connection, migratorChecks());
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(5);
            statement.execute("SET ROLE map_user_owner");
        }
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("migration_set_role", "SELECT current_user='map_user_owner' AND session_user='map_user_migrator'");
        checks.put("migration_owner_database", "SELECT NOT has_database_privilege(current_database(),'CREATE') AND NOT has_database_privilege(current_database(),'TEMP')");
        checks.put("migration_owner_cross_schema", "SELECT NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname<>'user_service' AND has_schema_privilege(oid,'CREATE'))");
        checks.putAll(crossSchemaChecks());
        checks.put("migration_objects_owned", "SELECT NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='user_service' AND c.relkind IN ('r','p','v','m','S','f') AND pg_get_userbyid(c.relowner)<>'map_user_owner')");
        check(connection, checks);
    }

    static void check(Connection connection, Map<String, String> checks) throws SQLException {
        for (var check : checks.entrySet()) {
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (var rows = statement.executeQuery(check.getValue())) {
                    require(rows.next() && rows.getBoolean(1) && !rows.wasNull() && !rows.next(), "database_privilege_" + check.getKey());
                }
            }
        }
    }
}

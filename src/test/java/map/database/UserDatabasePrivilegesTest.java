package map.database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Driver/branch contract tests, not a PostgreSQL ACL integration test. */
class UserDatabasePrivilegesTest {
    @Test
    void everyRuntimeAndMigratorGuardRejectsAnEffectivePrivilegeViolation() throws Exception {
        Map<String, String> checks = new LinkedHashMap<>(UserDatabasePrivileges.runtimeChecks());
        checks.putAll(UserDatabasePrivileges.migratorChecks());
        for (var check : checks.entrySet()) {
            var connection = mock(Connection.class);
            var statement = mock(Statement.class);
            var rows = mock(ResultSet.class);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(check.getValue())).thenReturn(rows);
            when(rows.next()).thenReturn(true);
            when(rows.getBoolean(1)).thenReturn(false);
            assertThatThrownBy(() -> UserDatabasePrivileges.check(connection, Map.of(check.getKey(), check.getValue())))
                    .hasMessage("database_privilege_" + check.getKey());
            verify(statement).setQueryTimeout(5);
            verify(statement).close();
            verify(rows).close();
            verify(statement, never()).execute(anyString());
        }
    }

    @Test
    void unknownMissingOrAmbiguousCatalogResultsFailClosed() throws Exception {
        for (String mode : new String[]{"missing", "null", "multiple"}) {
            var connection = mock(Connection.class);
            var statement = mock(Statement.class);
            var rows = mock(ResultSet.class);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery("SELECT safe_check")).thenReturn(rows);
            when(rows.next()).thenReturn(!mode.equals("missing"), mode.equals("multiple"));
            when(rows.getBoolean(1)).thenReturn(true);
            when(rows.wasNull()).thenReturn(mode.equals("null"));
            assertThatThrownBy(() -> UserDatabasePrivileges.check(connection, Map.of("required", "SELECT safe_check")))
                    .hasMessage("database_privilege_required");
        }
    }

    @Test
    void validatesAllChecksWithoutWritesForSafeRuntime() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(call -> {
            var rows = mock(ResultSet.class);
            when(rows.next()).thenReturn(true, false);
            when(rows.getBoolean(1)).thenReturn(true);
            return rows;
        });
        UserDatabasePrivileges.verifyRuntime(connection);
        verify(statement, times(UserDatabasePrivileges.runtimeChecks().size())).executeQuery(anyString());
        verify(statement, never()).execute(anyString());
    }
}

package map.service.user.global.config;

import map.database.UserDatabaseContract;
import map.database.UserDatabasePrivileges;
import org.springframework.core.env.Environment;
import java.util.Arrays;

/** Invoked by a static BeanFactoryPostProcessor, before serving singleton initialization. */
public final class RuntimeDatabaseGuard {
    private RuntimeDatabaseGuard() {}

    public static void verify(Environment environment) {
        UserDatabaseContract.require(!environment.getProperty("spring.flyway.enabled", Boolean.class, false), "serving_flyway_forbidden");
        String url = environment.getProperty("spring.datasource.url", "");
        // The production bootJar has no H2 driver. This exception is only for in-memory unit fixtures.
        if (url.startsWith("jdbc:h2:mem:") && Arrays.asList(environment.getActiveProfiles()).contains("test")) {
            try {
                Class.forName("org.h2.Driver");
                return;
            } catch (ClassNotFoundException error) {
                throw new IllegalStateException("test_database_unavailable");
            }
        }
        UserDatabaseContract.require("validate".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto")), "serving_ddl_forbidden");
        UserDatabaseContract.require(UserDatabaseContract.RUNTIME.equals(environment.getProperty("spring.datasource.username")), "runtime_role_required");
        UserDatabaseContract.require(environment.getProperty("USER_MIGRATION_PASSWORD", "").isEmpty(), "migration_secret_in_serving");
        UserDatabaseContract.require(environment.getProperty("POSTGRES_PASSWORD", "").isEmpty(), "shared_database_secret_in_serving");
        UserDatabaseContract.validateUrl(url);
        try (var connection = UserDatabaseContract.connect(url, UserDatabaseContract.RUNTIME,
                environment.getProperty("spring.datasource.password"))) {
            connection.setReadOnly(true);
            UserDatabasePrivileges.verifyRuntime(connection);
        } catch (java.sql.SQLException error) {
            // No JDBC exception causes/messages: those may contain connection details.
            throw new IllegalStateException("runtime_database_verification_failed");
        }
    }
}

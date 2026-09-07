package map.database;

import map.service.user.global.config.RuntimeDatabaseGuard;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class RuntimeDatabaseGuardTest {
    @Test
    void servingCannotOptBackIntoFlywayEvenInTests() {
        var environment = new MockEnvironment().withProperty("spring.flyway.enabled", "true");
        assertThatThrownBy(() -> RuntimeDatabaseGuard.verify(environment)).hasMessage("serving_flyway_forbidden");
    }

    @Test
    void servingRejectsSharedRoleAndDdlBeforeOpeningConnection() {
        var environment = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:1/synthetic")
                .withProperty("spring.jpa.hibernate.ddl-auto", "create")
                .withProperty("spring.datasource.username", "map");
        assertThatThrownBy(() -> RuntimeDatabaseGuard.verify(environment)).hasMessage("serving_ddl_forbidden");
        environment.setProperty("spring.jpa.hibernate.ddl-auto", "validate");
        assertThatThrownBy(() -> RuntimeDatabaseGuard.verify(environment)).hasMessage("runtime_role_required");
        environment.setProperty("spring.datasource.username", "map_user_runtime");
        environment.setProperty("USER_MIGRATION_PASSWORD", "synthetic");
        assertThatThrownBy(() -> RuntimeDatabaseGuard.verify(environment)).hasMessage("migration_secret_in_serving");
    }

    @Test
    void onlyExplicitInMemoryTestProfileCanSkipPostgresqlCatalogCheck() {
        var environment = new MockEnvironment().withProperty("spring.datasource.url", "jdbc:h2:mem:unit_only");
        assertThatThrownBy(() -> RuntimeDatabaseGuard.verify(environment)).hasMessage("serving_ddl_forbidden");
        environment.setActiveProfiles("test");
        assertThatCode(() -> RuntimeDatabaseGuard.verify(environment)).doesNotThrowAnyException();
        environment.setProperty("spring.datasource.url", "jdbc:h2:file:/tmp/not-allowed");
        assertThatThrownBy(() -> RuntimeDatabaseGuard.verify(environment)).hasMessage("serving_ddl_forbidden");
    }
}

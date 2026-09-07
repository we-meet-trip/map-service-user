package map.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class UserDatabaseContractTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:h2:mem:test", "jdbc:postgresql:map", "jdbc:postgresql://name:secret@localhost/map",
            "jdbc:postgresql://localhost/map?user=map", "jdbc:postgresql://localhost/map?password=synthetic",
            "jdbc:postgresql://localhost/map?currentSchema=public", "jdbc:postgresql://localhost/map?options=-crole=map",
            "jdbc:postgresql://localhost/map?currentSchema=user_service&currentSchema=user_service",
            "jdbc:postgresql://localhost/map?socketFactory=example.Class", "jdbc:postgresql://localhost/map#secret",
            "jdbc:postgresql://localhost/map?sslmode=prefer", "jdbc:postgresql://localhost/map?sslrootcert=relative"
    })
    void rejectsWrongSourceAndCredentialOrDriverOverrides(String url) {
        assertThatThrownBy(() -> UserDatabaseContract.validateUrl(url)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret").hasMessageNotContaining(url);
    }

    @Test
    void allowsExplicitSchemaAndTlsWithoutEmbeddingCredentials() {
        UserDatabaseContract.validateUrl("jdbc:postgresql://db.test:5432/map?currentSchema=user_service&sslmode=verify-full&sslrootcert=/run/trust/root.crt");
        var properties = UserDatabaseContract.connectionProperties("map_user_runtime", "synthetic-unit-only");
        assertThat(properties).containsEntry("connectTimeout", "5").containsEntry("socketTimeout", "30")
                .containsEntry("currentSchema", "user_service");
    }

    @Test
    void missingPasswordNeverFallsBackToSharedCredentials() {
        assertThatThrownBy(() -> UserDatabaseContract.connectionProperties("map_user_runtime", " "))
                .hasMessage("database_password_required");
    }
}

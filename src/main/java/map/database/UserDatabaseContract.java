package map.database;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/** No Spring dependency: shared by the serving pre-bean guard and standalone migrator. */
public final class UserDatabaseContract {
    public static final String SCHEMA = "user_service";
    public static final String RUNTIME = "map_user_runtime";
    public static final String MIGRATOR = "map_user_migrator";
    public static final String OWNER = "map_user_owner";
    private UserDatabaseContract() {}

    public static void validateUrl(String url) {
        try {
            require(url != null && url.startsWith("jdbc:postgresql://"), "postgresql_url_required");
            URI uri = new URI(url.substring(5));
            require(uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null
                    && uri.getPath() != null && uri.getPath().matches("/[A-Za-z0-9_-]+")
                    && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535), "postgresql_url_invalid");
            Set<String> seen = new HashSet<>();
            if (uri.getRawQuery() != null) for (String entry : uri.getRawQuery().split("&", -1)) {
                String[] pair = entry.split("=", -1);
                require(pair.length == 2, "postgresql_option_invalid");
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                require(seen.add(key), "postgresql_option_duplicate");
                switch (key) {
                    case "currentSchema" -> require(SCHEMA.equals(value), "schema_mismatch");
                    case "sslmode" -> require(Set.of("disable", "require", "verify-ca", "verify-full").contains(value), "sslmode_invalid");
                    case "sslrootcert" -> require(value.startsWith("/") && !value.contains("\n"), "sslrootcert_invalid");
                    default -> throw new IllegalStateException("postgresql_option_forbidden");
                }
            }
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("postgresql_url_invalid");
        }
    }

    public static Properties connectionProperties(String username, String password) {
        require(password != null && !password.isBlank(), "database_password_required");
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("currentSchema", SCHEMA);
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "30");
        properties.setProperty("ApplicationName", "map-user-privilege-check");
        return properties;
    }

    public static Connection connect(String url, String username, String password) throws SQLException {
        validateUrl(url);
        return DriverManager.getConnection(url, connectionProperties(username, password));
    }

    public static void require(boolean condition, String safeCode) {
        if (!condition) throw new IllegalStateException(safeCode);
    }
}

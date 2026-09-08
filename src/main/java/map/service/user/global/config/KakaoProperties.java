package map.service.user.global.config;

import java.net.URI;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "kakao")
public class KakaoProperties implements InitializingBean {

    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String authorizeUri;
    private String tokenUri;
    private String userInfoUri;
    private String nativeEnvironment = "local";
    private String publicOrigin;
    private boolean authEnforced;

    /** Complete fixed callback URI, not a caller-selected scheme. */
    private String appCallbackScheme;

    @Override
    public void afterPropertiesSet() {
        // A disabled provider must not prevent email login or the isolated CI fixture.
        if (clientId == null || clientId.isBlank()) return;
        if ("local".equals(nativeEnvironment) && !authEnforced) {
            URI redirect = parse(redirectUri);
            require("http".equals(redirect.getScheme())
                    && Set.of("localhost", "127.0.0.1", "[::1]").contains(redirect.getHost())
                    && clean(redirect) && "/api/v1/auth/kakao/callback".equals(redirect.getRawPath()),
                    "Local Kakao redirect must use the loopback callback");
            require("mapauth-test://kakao".equals(appCallbackScheme),
                    "Local Kakao callback must use the test app");
            return;
        }
        require(Set.of("test", "prod").contains(nativeEnvironment),
                "Configured Kakao requires APP_ENV=test or prod");
        URI origin = parse(publicOrigin);
        require("https".equals(origin.getScheme()) && clean(origin)
                && origin.getHost() != null && origin.getHost().contains(".")
                && origin.getPort() == -1 && origin.getRawPath().isEmpty()
                && !origin.getHost().matches("[0-9.]+")
                && !origin.getHost().endsWith(".localhost"),
                "KAKAO_PUBLIC_ORIGIN must be an explicit HTTPS DNS origin without a port or path");
        require((publicOrigin + "/api/v1/auth/kakao/callback").equals(redirectUri),
                "KAKAO_OAUTH_REDIRECT_URI must match the public origin callback exactly");
        require(("test".equals(nativeEnvironment) ? "mapauth-test://kakao" : "mapauth://kakao")
                .equals(appCallbackScheme), "Kakao app callback must match APP_ENV");
        if ("prod".equals(nativeEnvironment)) {
            String host = origin.getHost().toLowerCase(java.util.Locale.ROOT);
            require(!host.equals("mapapptest.duckdns.org") && !host.endsWith(".web.app")
                    && !host.endsWith(".firebaseapp.com"), "Production Kakao forbids known test origins");
        }
        require("https://kauth.kakao.com/oauth/authorize".equals(authorizeUri)
                && "https://kauth.kakao.com/oauth/token".equals(tokenUri)
                && "https://kapi.kakao.com/v2/user/me".equals(userInfoUri),
                "Deployed Kakao endpoints must use the fixed provider HTTPS APIs");
    }

    private static boolean clean(URI uri) {
        return uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
    }

    private static URI parse(String value) {
        try {
            require(value != null && !value.isBlank() && value.equals(value.trim()), "Kakao URI is missing or invalid");
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Kakao URI is invalid");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

package map.service.user.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.*;

class KakaoPropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KakaoProperties.class)
    static class Config {}

    private ApplicationContextRunner configured(String environment, String origin, String callback) {
        return new ApplicationContextRunner().withUserConfiguration(Config.class).withPropertyValues(
                "kakao.client-id=synthetic-id", "kakao.auth-enforced=true",
                "kakao.native-environment=" + environment, "kakao.public-origin=" + origin,
                "kakao.redirect-uri=" + origin + "/api/v1/auth/kakao/callback",
                "kakao.app-callback-scheme=" + callback,
                "kakao.authorize-uri=https://kauth.kakao.com/oauth/authorize",
                "kakao.token-uri=https://kauth.kakao.com/oauth/token",
                "kakao.user-info-uri=https://kapi.kakao.com/v2/user/me");
    }

    @Test void testAndProductionRequireTheirOwnFixedAppCallback() {
        configured("test", "https://mapapptest.duckdns.org", "mapauth-test://kakao")
                .run(context -> assertThat(context).hasNotFailed());
        configured("prod", "https://prod.example.invalid", "mapauth://kakao")
                .run(context -> assertThat(context).hasNotFailed());
        configured("test", "https://mapapptest.duckdns.org", "mapauth://kakao")
                .run(context -> assertThat(context).hasFailed());
        configured("prod", "https://prod.example.invalid", "mapauth-test://kakao")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void configuredProviderCannotSilentlyUseLocalOrUnknownDeployment() {
        for (String environment : new String[] {"local", "", "production", "TEST"})
            configured(environment, "https://prod.example.invalid", "mapauth://kakao")
                    .run(context -> assertThat(context).hasFailed());
    }

    @Test void deploymentRejectsNonOriginsAndRedirectByteMismatch() {
        for (String origin : new String[] {"", "http://example.invalid", "https://user@example.invalid",
                "https://example.invalid/", "https://example.invalid/path", "https://example.invalid?x=1",
                "https://example.invalid#x", "https://example.invalid:443", "https://127.0.0.1",
                "https://localhost", "https://x.localhost", "https://example.invalid "})
            configured("test", origin, "mapauth-test://kakao").run(context -> assertThat(context).hasFailed());
        configured("test", "https://test.example.invalid", "mapauth-test://kakao")
                .withPropertyValues("kakao.redirect-uri=https://other.example.invalid/api/v1/auth/kakao/callback")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void productionCannotUseKnownTestHosts() {
        for (String host : new String[] {"mapapptest.duckdns.org", "mapcenter-b59ca.web.app", "mapcenter-b59ca.firebaseapp.com"})
            configured("prod", "https://" + host, "mapauth://kakao").run(context -> assertThat(context).hasFailed());
    }

    @Test void callbackCannotCarryInjectedQueriesPathsOrUserinfo() {
        for (String callback : new String[] {"mapauth-test://kakao?state=x", "mapauth-test://evil", "mapauth-test://kakao/extra",
                "mapauth-test://user@kakao", "mapauth-test://kakao#x", "mapauth-test://kakao:2"})
            configured("test", "https://test.example.invalid", callback).run(context -> assertThat(context).hasFailed());
    }

    @Test void configuredProviderDoesNotSendCredentialsToOverriddenApi() {
        for (String key : new String[] {"authorize-uri", "token-uri", "user-info-uri"})
            configured("test", "https://test.example.invalid", "mapauth-test://kakao")
                    .withPropertyValues("kakao." + key + "=https://other.example.invalid/")
                    .run(context -> assertThat(context).hasFailed());
    }

    @Test void disabledProviderDoesNotBreakOtherAuthentication() {
        new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withPropertyValues("kakao.auth-enforced=true", "kakao.client-id=")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test void localDevelopmentIsExplicitlyLoopbackAndTestAppOnly() {
        configured("local", "", "mapauth-test://kakao")
                .withPropertyValues("kakao.auth-enforced=false", "kakao.redirect-uri=http://localhost:8080/api/v1/auth/kakao/callback")
                .run(context -> assertThat(context).hasNotFailed());
        configured("local", "", "mapauth-test://kakao")
                .withPropertyValues("kakao.auth-enforced=false", "kakao.redirect-uri=http://outside.example.invalid/api/v1/auth/kakao/callback")
                .run(context -> assertThat(context).hasFailed());
    }
}

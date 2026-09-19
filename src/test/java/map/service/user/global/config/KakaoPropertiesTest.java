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

    private ApplicationContextRunner configured(String appId) {
        return new ApplicationContextRunner().withUserConfiguration(Config.class).withPropertyValues(
                "kakao.app-id=" + appId,
                "kakao.token-info-uri=https://kapi.kakao.com/v1/user/access_token_info",
                "kakao.user-info-uri=https://kapi.kakao.com/v2/user/me");
    }

    @Test void configuredProviderStartsWithTheFixedLookupEndpoints() {
        configured("1443373").run(context -> assertThat(context).hasNotFailed());
    }

    @Test void applicationIdentifierMustBeAPositiveNumber() {
        for (String appId : new String[] {"0", "-1"})
            configured(appId).run(context -> assertThat(context).hasFailed());
    }

    @Test void configuredProviderDoesNotSendTheUserTokenToAnOverriddenApi() {
        for (String key : new String[] {"token-info-uri", "user-info-uri"})
            configured("1443373").withPropertyValues("kakao." + key + "=https://other.example.invalid/")
                    .run(context -> assertThat(context).hasFailed());
        // 같은 호스트라도 경로가 다르면 다른 API 다.
        configured("1443373")
                .withPropertyValues("kakao.token-info-uri=https://kapi.kakao.com/v2/user/me")
                .run(context -> assertThat(context).hasFailed());
        for (String blank : new String[] {"", " "})
            configured("1443373").withPropertyValues("kakao.user-info-uri=" + blank)
                    .run(context -> assertThat(context).hasFailed());
    }

    @Test void disabledProviderDoesNotBreakOtherAuthentication() {
        new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withPropertyValues("kakao.app-id=")
                .run(context -> assertThat(context).hasNotFailed());
    }
}

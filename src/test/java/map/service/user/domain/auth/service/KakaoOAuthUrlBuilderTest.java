package map.service.user.domain.auth.service;

import map.service.user.global.config.KakaoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KakaoOAuthService 의 URL 조립 로직(authorize URL / 앱 콜백 바운스 Location) 단위 테스트.
 * repository/RestClient 는 사용하지 않으므로 실제 KakaoProperties 만 주입한다.
 */
@DisplayName("KakaoOAuthService URL 빌더 단위 테스트")
class KakaoOAuthUrlBuilderTest {

    private KakaoOAuthService service;

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    @BeforeEach
    void setUp() {
        KakaoProperties props = new KakaoProperties();
        props.setClientId("rest-key-123");
        props.setRedirectUri("https://test.example.com/api/v1/auth/kakao/callback");
        props.setAuthorizeUri("https://kauth.kakao.com/oauth/authorize");
        props.setAppCallbackScheme("mapauth://kakao");
        service = new KakaoOAuthService(props, null, null, null, null, null);
    }

    @Test
    @DisplayName("authorize URL — redirect_uri/state 를 URL-encode 하고 필수 파라미터를 포함한다")
    void buildAuthorizeUrl_encodesAndIncludesRequiredParams() {
        String state = "st/at e+1";
        String url = service.buildAuthorizeUrl(state);

        assertThat(url).startsWith("https://kauth.kakao.com/oauth/authorize?");
        assertThat(url).contains("client_id=rest-key-123");
        // redirect_uri 는 토큰 교환값과 byte 단위 동일해야 하므로 인코딩된 원문 그대로 포함되어야 한다
        assertThat(url).contains("redirect_uri="
                + enc("https://test.example.com/api/v1/auth/kakao/callback"));
        assertThat(url).contains("response_type=code");
        assertThat(url).contains("scope=profile_nickname");
        assertThat(url).contains("state=" + enc(state));
    }

    @Test
    @DisplayName("앱 콜백 Location — 성공 시 code/state 를 인코딩해 고정 스킴으로 조립한다")
    void buildAppCallbackLocation_success() {
        String loc = service.buildAppCallbackLocation("code123", "st/at e", null, null);
        assertThat(loc).isEqualTo("mapauth://kakao?code=code123&state=" + enc("st/at e"));
    }

    @Test
    @DisplayName("앱 콜백 Location — error 우선. error 존재 시 code 대신 error/error_description 전달")
    void buildAppCallbackLocation_error() {
        String loc = service.buildAppCallbackLocation(null, null, "access_denied", "User denied");
        assertThat(loc).isEqualTo(
                "mapauth://kakao?error=access_denied&error_description=" + enc("User denied"));
    }

    @Test
    @DisplayName("앱 콜백 Location — 리다이렉트 대상 스킴은 항상 설정값으로 고정(open-redirect 없음)")
    void buildAppCallbackLocation_alwaysFixedScheme() {
        String loc = service.buildAppCallbackLocation("evil://attacker", "s", null, null);
        assertThat(loc).startsWith("mapauth://kakao?");
        // 공격자 문자열은 code 값으로 인코딩되어 스킴을 바꾸지 못한다
        assertThat(loc).contains("code=" + enc("evil://attacker"));
    }
}

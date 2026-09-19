package map.service.user.global.config;

import java.net.URI;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카카오 로그인 설정.
 *
 * 앱이 카카오 SDK 로 직접 로그인해 액세스 토큰을 들고 오므로, 서버는 인가 주소를
 * 만들지도 인가 코드를 토큰으로 바꾸지도 않는다. 서버가 하는 일은 받은 토큰이
 * <b>우리 앱에 발급된 것인지</b> 확인하고 그 토큰으로 사용자 정보를 읽는 것뿐이라,
 * 필요한 값도 앱 식별자와 두 개의 조회 주소로 줄었다.
 *
 * appId 가 비어 있으면 카카오 로그인이 꺼진 것으로 본다. 이메일 로그인과 CI 픽스처는
 * 카카오 설정 없이도 떠야 하기 때문이다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "kakao")
public class KakaoProperties implements InitializingBean {

    /** 카카오가 앱마다 부여하는 번호. 받은 토큰이 이 앱 것인지 대조하는 기준이다. */
    private Long appId;

    /** 토큰 정보 보기. 토큰에 묶인 앱 번호와 회원번호를 돌려준다. */
    private String tokenInfoUri;

    /** 사용자 정보 가져오기. 닉네임 등 프로필을 돌려준다. */
    private String userInfoUri;

    @Override
    public void afterPropertiesSet() {
        // 꺼진 제공자가 다른 로그인 수단까지 막아서는 안 된다.
        if (appId == null) return;
        require(appId > 0, "KAKAO_APP_ID must be a positive application identifier");
        // 조회 주소가 바뀌면 토큰이 어디로 나가는지가 바뀐다. 값으로 열어 두지 않고 고정한다.
        require(fixed(tokenInfoUri, "https://kapi.kakao.com/v1/user/access_token_info")
                && fixed(userInfoUri, "https://kapi.kakao.com/v2/user/me"),
                "Kakao lookup endpoints must use the fixed provider HTTPS APIs");
    }

    private static boolean fixed(String value, String expected) {
        if (value == null || !value.equals(expected)) return false;
        URI uri = URI.create(value);
        return "https".equals(uri.getScheme()) && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null && uri.getRawFragment() == null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

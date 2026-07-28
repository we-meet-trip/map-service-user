package map.service.user.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "kakao")
public class KakaoProperties {

    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String authorizeUri;
    private String tokenUri;
    private String userInfoUri;

    /** 인가 코드 캡처 후 앱으로 복귀시킬 커스텀 스킴 (예: mapauth://kakao). GET 바운스 콜백이 이 스킴으로 302 재작성한다. */
    private String appCallbackScheme;
}

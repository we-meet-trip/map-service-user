package map.service.user.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** Base64-encoded PKCS8 RSA private key (PEM 헤더 제외). 미설정 시 임시 키 쌍 자동 생성. */
    private String privateKey;

    /** Base64-encoded X509 RSA public key (PEM 헤더 제외). 미설정 시 임시 키 쌍 자동 생성. */
    private String publicKey;

    /** access token 유효 시간 (초). 기본 30분. */
    private long accessTokenExpirySeconds = 1800;

    /** refresh token 유효 시간 (초). 기본 14일. */
    private long refreshTokenExpirySeconds = 1209600;
}

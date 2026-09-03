package map.service.user.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LocationCryptoProperties — 저장 시점 암호화 키 설정
 *
 * 키는 실행 환경에서 문자열로 들어온다. 여러 개를 동시에 들고 있는 이유는
 * 키를 바꾸는 동안에도 옛 키로 쓴 값을 읽어야 하기 때문이다. 암호문에 어떤
 * 키로 썼는지가 함께 적히므로, 읽을 때는 그 표식으로 골라 쓰고 쓸 때는
 * activeKid 하나만 쓴다.
 *
 * enabled: 끄면 새로 쓰는 값은 평문이 된다. 이미 봉투로 저장된 값은 키가
 *          남아 있는 한 계속 읽힌다. 키까지 없으면 읽기가 실패한다 —
 *          평문처럼 보이는 암호문을 돌려주면 그 값이 다음 저장에 섞여
 *          되돌릴 수 없게 망가지기 때문이다.
 * activeKid: 새로 쓸 때 사용할 키의 이름.
 * keys: "이름:base64키,이름:base64키" 형식. 키는 풀었을 때 32바이트여야 한다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "location.crypto")
public class LocationCryptoProperties {

    private boolean enabled = true;

    private String activeKid = "";

    private String keys = "";
}

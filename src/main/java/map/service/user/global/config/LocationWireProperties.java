package map.service.user.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LocationWireProperties — 서비스 사이로 나가는 좌표를 감싸는 설정
 *
 * 저장할 때 쓰는 열쇠와 따로 둔다. 쓰임이 다르고 나눠 가지는 상대도 다르기
 * 때문이다. 저장 열쇠는 이 서비스 혼자 쓰지만, 이 열쇠는 좌표를 받아 실제로
 * 써야 하는 서비스들이 함께 가진다. 하나로 합치면 그중 한 서비스가 뚫렸을 때
 * 저장된 것까지 함께 열린다.
 *
 * enabled: 끄면 좌표를 예전처럼 값 그대로 보낸다. 상대 서비스가 아직 봉투를
 *          열 줄 모르는 동안 넘어가기 위한 스위치다. 양쪽이 준비되면 켠다.
 * key: 32바이트로 풀리는 base64. 켜 두었는데 없으면 부팅을 멈춘다.
 * max-age-seconds: 열 때 보는 봉투 나이 상한(초). 이 길로 오는 값은 스트림에
 *          쌓였다가 뒤늦게 처리될 수 있어 보내는 쪽 만료보다 느슨하게 둔다.
 *          0 이면 나이를 보지 않는다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "location.wire")
public class LocationWireProperties {

    private boolean enabled = true;

    private String key = "";

    private long maxAgeSeconds = 3600;
}

package map.service.user.global.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import map.service.user.global.config.LocationCryptoProperties;

/**
 * 테스트에서 쓸 PayloadCipher 를 만든다.
 *
 * 키 값을 여기 두어도 되는 이유는 이 키로 감싼 것이 테스트 프로세스 밖으로
 * 나가지 않기 때문이다. 대신 실제와 같은 32바이트 길이를 쓴다 — 길이가 다르면
 * 설정 검증에 걸려, 정작 검증하려던 감싸기·풀기 동작을 못 보게 된다.
 */
public final class TestPayloadCiphers {

    /** 32바이트로 풀리는 고정 키. */
    private static final String KEY = "bWFwLXNlcnZpY2UtdXNlci10ZXN0LWtleS0wMDAwMDA=";

    private TestPayloadCiphers() {
    }

    /** 감싸기가 켜진 것. 저장 값이 실제로 봉투가 된다. */
    public static PayloadCipher enabled() {
        return build(true);
    }

    /** 감싸기가 꺼진 것. 값이 그대로 지나가므로 기존 기대값을 그대로 쓸 수 있다. */
    public static PayloadCipher disabled() {
        return build(false);
    }

    private static PayloadCipher build(boolean enabled) {
        LocationCryptoProperties properties = new LocationCryptoProperties();
        properties.setEnabled(enabled);
        properties.setActiveKid("test");
        properties.setKeys("test:" + KEY);
        PayloadCipher cipher = new PayloadCipher(properties, new ObjectMapper());
        cipher.init();
        return cipher;
    }
}

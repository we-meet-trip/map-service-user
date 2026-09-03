package map.service.user.global.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import map.service.user.global.config.LocationCryptoProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayloadCipherTest {

    private static final String PLAIN =
            "{\"places\":[{\"name\":\"속초해수욕장\",\"lat\":38.1907,\"lng\":128.5998}]}";
    private static final String AAD = PayloadCipher.aad("schedules", "payload", "7");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("감싼 뒤 풀면 원래 본문이 나온다")
    void roundTrip() {
        PayloadCipher cipher = TestPayloadCiphers.enabled();

        String sealed = cipher.encrypt(PLAIN, AAD);

        assertThat(sealed).isNotEqualTo(PLAIN);
        assertThat(cipher.decrypt(sealed, AAD)).isEqualTo(PLAIN);
    }

    @Test
    @DisplayName("감싼 값에는 좌표도 장소 이름도 남지 않는다")
    void sealedValueLeaksNothing() {
        String sealed = TestPayloadCiphers.enabled().encrypt(PLAIN, AAD);

        assertThat(sealed).doesNotContain("38.1907", "128.5998", "속초해수욕장", "places");
    }

    @Test
    @DisplayName("같은 본문을 두 번 감싸도 결과가 다르다")
    void neverRepeatsCiphertext() {
        PayloadCipher cipher = TestPayloadCiphers.enabled();

        // 난수를 매번 새로 뽑는지 보는 검사다. 이 값이 같아지면 GCM 이 위조를
        // 막는 성질을 잃으므로, 같은 결과가 나오는 순간 실패해야 한다.
        assertThat(cipher.encrypt(PLAIN, AAD)).isNotEqualTo(cipher.encrypt(PLAIN, AAD));
    }

    @Test
    @DisplayName("다른 자리의 암호문은 열리지 않는다")
    void rejectsRelocatedCiphertext() {
        PayloadCipher cipher = TestPayloadCiphers.enabled();
        String sealedForSeven = cipher.encrypt(PLAIN, AAD);

        // 7번 사용자의 본문을 8번 행에 옮겨 넣은 상황. 암호문 자체는 멀쩡하므로
        // 자리를 묶어 두지 않으면 그대로 열린다.
        String otherRow = PayloadCipher.aad("schedules", "payload", "8");

        assertThatThrownBy(() -> cipher.decrypt(sealedForSeven, otherRow))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("아직 감싸지 않은 값은 그대로 읽힌다")
    void passesThroughPlainValue() {
        assertThat(TestPayloadCiphers.enabled().decrypt(PLAIN, AAD)).isEqualTo(PLAIN);
    }

    @Test
    @DisplayName("꺼 두어도 이미 감싼 값은 읽힌다")
    void stillReadsSealedValueWhenDisabled() {
        String sealed = TestPayloadCiphers.enabled().encrypt(PLAIN, AAD);
        PayloadCipher off = TestPayloadCiphers.disabled();

        assertThat(off.encrypt(PLAIN, AAD)).isEqualTo(PLAIN);
        assertThat(off.decrypt(sealed, AAD)).isEqualTo(PLAIN);
    }

    @Test
    @DisplayName("JSON 노드도 같은 방식으로 오간다")
    void roundTripsNode() throws Exception {
        PayloadCipher cipher = TestPayloadCiphers.enabled();

        var sealed = cipher.encryptNode(objectMapper.readTree(PLAIN), AAD);

        assertThat(cipher.isEnvelope(sealed)).isTrue();
        assertThat(cipher.decryptNode(sealed, AAD)).isEqualTo(objectMapper.readTree(PLAIN));
    }

    @Test
    @DisplayName("키 길이가 32바이트가 아니면 기동을 멈춘다")
    void refusesShortKey() {
        LocationCryptoProperties properties = new LocationCryptoProperties();
        properties.setEnabled(true);
        properties.setActiveKid("k");
        // 32글자 문자열을 그대로 붙여 넣은 흔한 실수. 풀면 24바이트가 된다.
        properties.setKeys("k:" + java.util.Base64.getEncoder()
                .encodeToString("0123456789012345678901234".getBytes()));

        assertThatThrownBy(() -> new PayloadCipher(properties, objectMapper).init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("켜 두었는데 키가 없으면 기동을 멈춘다")
    void refusesMissingKey() {
        LocationCryptoProperties properties = new LocationCryptoProperties();
        properties.setEnabled(true);

        assertThatThrownBy(() -> new PayloadCipher(properties, objectMapper).init())
                .isInstanceOf(IllegalStateException.class);
    }
}

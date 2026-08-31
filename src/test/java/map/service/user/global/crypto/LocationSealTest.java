package map.service.user.global.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import map.service.user.global.config.LocationWireProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LocationSealTest {

    /** 검사에서만 쓰는 고정 열쇠. hub 쪽 검사도 같은 값을 쓴다. */
    static final String KEY = "bWFwLXdpcmUtdGVzdC1rZXktMDAwMDAwMDAwMDAwMCE=";

    private static LocationSeal seal(boolean enabled, String key) {
        LocationWireProperties properties = new LocationWireProperties();
        properties.setEnabled(enabled);
        properties.setKey(key);
        LocationSeal seal = new LocationSeal(properties, new ObjectMapper());
        seal.init();
        return seal;
    }

    @Test
    @DisplayName("감싼 값에는 좌표가 남지 않는다")
    void sealedValueHidesCoordinates() {
        String token = seal(true, KEY).seal(35.1587, 129.1604);

        assertThat(token).startsWith("v1.").doesNotContain("35.1587", "129.1604");
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    @DisplayName("요청 줄에 그대로 실을 수 있는 글자만 쓴다")
    void tokenIsUrlSafe() {
        // 더하기·빗금·채움문자가 섞이면 주소에서 다른 뜻으로 읽혀, 받는 쪽이
        // 다른 값을 열게 된다. 그 어긋남은 복호 실패로만 드러나 원인을 찾기 어렵다.
        String token = seal(true, KEY).seal(37.5665, 126.9780);

        assertThat(token).matches("v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("같은 좌표를 두 번 감싸도 결과가 다르다")
    void neverRepeats() {
        LocationSeal s = seal(true, KEY);

        assertThat(s.seal(35.1587, 129.1604)).isNotEqualTo(s.seal(35.1587, 129.1604));
    }

    @Test
    @DisplayName("두 지점을 담는 봉투도 좌표를 드러내지 않는다")
    void sealsPair() {
        String token = seal(true, KEY).sealPair(37.5665, 126.9780, 35.1587, 129.1604);

        assertThat(token).doesNotContain("37.5665", "126.978", "35.1587", "129.1604");
    }

    @Test
    @DisplayName("켜 두었는데 열쇠가 없으면 기동을 멈춘다")
    void refusesMissingKey() {
        assertThatThrownBy(() -> seal(true, ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("열쇠가 32바이트가 아니면 기동을 멈춘다")
    void refusesShortKey() {
        String short24 = Base64.getEncoder()
                .encodeToString("0123456789012345678901234".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> seal(true, short24))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("다른 언어 쪽에서 열어 보도록 봉투 하나를 남긴다")
    void writesTokenForCrossLanguageCheck() throws Exception {
        // 받는 쪽은 파이썬으로 되어 있어 같은 검사 틀 안에서 열어 볼 수 없다.
        // 형식이 어긋나면 배포한 뒤 모든 좌표 요청이 한꺼번에 거절되므로,
        // 여기서 만든 값을 파일로 남겨 상대 쪽 검사가 열어 보게 한다.
        Path out = Path.of("build", "seal-interop.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, seal(true, KEY).seal(35.1587, 129.1604));

        assertThat(Files.readString(out)).startsWith("v1.");
    }
}

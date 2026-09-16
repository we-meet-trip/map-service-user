package map.service.user.global.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AuthStartupValidatorTest — 인가 시행 전제 조건 부팅 검증 단위 테스트
 *
 * enforced=true 조건에서 JWT 키 미설정 / CORS 와일드카드 / 내부 서비스 토큰 미설정을
 * 각각 차단하는지, enforced=false 에서는 어떤 설정이든 통과(no-op)하는지 검증한다.
 */
@DisplayName("AuthStartupValidator 단위 테스트")
class AuthStartupValidatorTest {

    private static JwtProperties jwtProps(String priv, String pub) {
        JwtProperties props = new JwtProperties();
        props.setPrivateKey(priv);
        props.setPublicKey(pub);
        return props;
    }

    private static CorsProperties corsProps(List<String> origins) {
        CorsProperties props = new CorsProperties();
        props.setAllowedOrigins(origins);
        return props;
    }

    @Test
    @DisplayName("enforced=true + JWT 키 미설정 → IllegalStateException")
    void enforcedWithBlankKeysThrows() {
        AuthStartupValidator validator = new AuthStartupValidator(
                true, jwtProps("", ""), corsProps(List.of("https://map.example.com")), "tok", "tok");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY/JWT_PUBLIC_KEY");
    }

    @Test
    @DisplayName("enforced=true + private-key 만 누락 → IllegalStateException")
    void enforcedWithMissingPrivateKeyThrows() {
        AuthStartupValidator validator = new AuthStartupValidator(
                true, jwtProps(null, "pub"), corsProps(List.of("https://map.example.com")), "tok", "tok");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY/JWT_PUBLIC_KEY");
    }

    @Test
    @DisplayName("enforced=true + CORS 와일드카드 → IllegalStateException")
    void enforcedWithWildcardCorsThrows() {
        AuthStartupValidator validator = new AuthStartupValidator(
                true, jwtProps("priv", "pub"), corsProps(List.of("*")), "tok", "tok");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORS wildcard");
    }

    @Test
    @DisplayName("enforced=true + 키 설정 + 명시 출처 → 통과")
    void enforcedWithValidConfigPasses() {
        AuthStartupValidator validator = new AuthStartupValidator(
                true, jwtProps("priv", "pub"), corsProps(List.of("https://map.example.com")), "tok", "tok");

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforced=true + 내부 서비스 토큰 미설정 → IllegalStateException")
    void enforcedWithBlankInternalTokenThrows() {
        // 토큰이 비면 hub·agent 호출에 헤더를 안 붙이는데 두 서비스는 그것을
        // 요구한다. 그대로 뜨면 장애가 조회 결과가 비는 모습으로만 드러난다.
        AuthStartupValidator validator = new AuthStartupValidator(
                true, jwtProps("priv", "pub"),
                corsProps(List.of("https://map.example.com")), "", "tok");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_SERVICE_TOKEN");
    }

    @Test
    @DisplayName("enforced=true + agent 토큰만 누락 → IllegalStateException")
    void enforcedWithBlankAgentTokenThrows() {
        AuthStartupValidator validator = new AuthStartupValidator(
                true, jwtProps("priv", "pub"),
                corsProps(List.of("https://map.example.com")), "tok", null);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_SERVICE_TOKEN");
    }

    @Test
    @DisplayName("enforced=false → 키/출처 무관하게 no-op 통과")
    void disabledIsNoOp() {
        AuthStartupValidator validator = new AuthStartupValidator(
                false, jwtProps("", ""), corsProps(List.of("*")), "", "");

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }
}

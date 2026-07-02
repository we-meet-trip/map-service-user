package map.service.user.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RS256 JWT 서명에 사용할 RSA KeyPair를 제공한다.
 *
 * - 운영: JWT_PRIVATE_KEY / JWT_PUBLIC_KEY 환경변수에 Base64-encoded PEM 설정
 * - 개발/테스트: 키 미설정 시 임시 2048-bit RSA 키 쌍 자동 생성 (경고 로그 출력)
 */
@Slf4j
@Configuration
public class JwtConfig {

    @Bean
    public KeyPair jwtKeyPair(JwtProperties props) {
        if (hasText(props.getPrivateKey()) && hasText(props.getPublicKey())) {
            return loadKeyPairFromConfig(props);
        }
        log.warn("JWT RSA 키가 설정되지 않아 임시 키 쌍을 생성합니다. 운영 환경에서는 반드시 JWT_PRIVATE_KEY / JWT_PUBLIC_KEY 를 설정하세요.");
        return generateEphemeralKeyPair();
    }

    private KeyPair loadKeyPairFromConfig(JwtProperties props) {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");

            byte[] privateBytes = Base64.getDecoder().decode(sanitizePem(props.getPrivateKey()));
            PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));

            byte[] publicBytes = Base64.getDecoder().decode(sanitizePem(props.getPublicKey()));
            PublicKey publicKey = kf.generatePublic(new X509EncodedKeySpec(publicBytes));

            return new KeyPair(publicKey, privateKey);
        } catch (Exception e) {
            throw new IllegalStateException("JWT RSA 키 로드 실패: " + e.getMessage(), e);
        }
    }

    private KeyPair generateEphemeralKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("RSA KeyPair 생성 실패", e);
        }
    }

    /** PEM 헤더/개행 제거 */
    private String sanitizePem(String pem) {
        return pem.replaceAll("-----.*?-----", "").replaceAll("\\s", "");
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}

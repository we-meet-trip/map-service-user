package map.service.user.global.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 양방향 암호화 컨버터.
 * JPA 컬럼에 @Convert(converter = AesEncryptionConverter.class) 적용.
 * 환경변수 OAUTH_TOKEN_ENCRYPTION_KEY: Base64-encoded 32바이트 키.
 */
@Converter
@Component
public class AesEncryptionConverter implements AttributeConverter<String, String> {

    private static final String ALGORITHM   = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN  = 12;  // 96-bit IV
    private static final int    GCM_TAG_LEN = 128; // 128-bit auth tag
    private static final String DELIMITER   = ":";

    private final SecretKey secretKey;

    public AesEncryptionConverter(
            @Value("${encryption.oauth-token-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            // 키 미설정 시 암호화 비활성화 (로컬 개발 편의)
            this.secretKey = null;
            return;
        }
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "OAUTH_TOKEN_ENCRYPTION_KEY must be 32 bytes (Base64-encoded). Got: " + keyBytes.length);
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null) return null;
        if (secretKey == null) return plaintext;

        try {
            byte[] iv = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(iv)
                    + DELIMITER
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbValue) {
        if (dbValue == null) return null;
        if (secretKey == null) return dbValue;

        try {
            String[] parts = dbValue.split(DELIMITER, 2);
            if (parts.length < 2) return dbValue;  // 암호화되지 않은 레거시 데이터 대응
            byte[] iv         = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] plainBytes = cipher.doFinal(ciphertext);

            return new String(plainBytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}

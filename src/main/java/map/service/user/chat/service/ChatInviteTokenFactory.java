package map.service.user.chat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * ChatInviteTokenFactory — 초대 토큰 생성·해싱
 *
 * 초대 링크에 담기는 원시 토큰을 만들고, 저장용 해시로 변환한다. 원시 토큰은
 * 발급 시 소유자에게 한 번만 노출되고 서버에는 해시만 저장하므로, 링크가 유출되어도
 * 저장소만으로는 토큰을 역산할 수 없다.
 *
 * newRawToken: 256비트 난수를 만들어 URL 에 넣기 안전한 base64(패딩 제거)로 인코딩한다.
 * hash: 원시 토큰을 SHA-256 hex(64자)로 해싱한다. 링크로 들어온 토큰을 같은 방식으로
 *       해싱해 저장된 해시와 대조하는 식으로 방을 찾는다.
 */
@Component
public class ChatInviteTokenFactory {

    /** 토큰 엔트로피 바이트 수. 32바이트 = 256비트. */
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    public String newRawToken() {
        byte[] buffer = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}

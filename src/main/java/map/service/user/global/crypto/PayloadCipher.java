package map.service.user.global.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import map.service.user.global.config.LocationCryptoProperties;
import org.springframework.stereotype.Component;

/**
 * PayloadCipher — 저장 직전에 본문을 봉투로 감싸고, 읽을 때 되돌린다
 *
 * 일정과 추천 결과 본문에는 사용자가 어디를 언제 다니는지가 좌표와 함께 들어
 * 있다. 이 본문이 저장소에 평문으로 남으면 저장소 파일이나 백업이 통째로
 * 빠져나갔을 때 그대로 읽힌다. 그것을 막는 것이 이 클래스의 목적이다.
 *
 * 봉투 형태는 다음과 같다.
 *   {"v":1,"kid":"키이름","iv":"base64","ct":"base64"}
 * 컬럼 타입을 바꾸지 않으려고 봉투 자체를 JSON 으로 둔다. 저장 자리가
 * jsonb 든 문자열이든 같은 모양이 들어가므로 다루는 길이 하나로 유지된다.
 *
 * 매 암호화마다 12바이트 난수를 새로 뽑는다. 같은 키로 같은 난수를 두 번
 * 쓰면 GCM 은 위조를 막는 성질을 잃는다. 그래서 난수를 저장하거나 재사용하지
 * 않고, 부를 때마다 뽑아 봉투에 함께 담는다(난수는 비밀이 아니다).
 *
 * aad 는 이 암호문이 어느 자리의 것인지를 함께 묶는 값이다. 없으면 A 사용자의
 * 암호문을 B 의 행에 옮겨 넣어도 정상적으로 풀린다 — 암호문 자체는 멀쩡하고
 * 자리만 틀린 상태라 무결성 검사가 잡지 못한다. 그래서 행을 지목하는 값을
 * 넣되, 행이 사는 동안 바뀌지 않는 것만 쓴다. 바뀌는 값을 묶으면 그 값을
 * 고치는 순간 과거 행이 열리지 않는다.
 *
 * 봉투가 아닌 값을 만나면 그대로 돌려준다. 이미 평문으로 쌓여 있는 행을
 * 한 번에 바꾸지 않고, 읽고 다시 쓰는 흐름에서 자연히 넘어가게 하려는 것이다.
 */
@Component
public class PayloadCipher {

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final int VERSION = 1;

    private static final String F_VERSION = "v";
    private static final String F_KID = "kid";
    private static final String F_IV = "iv";
    private static final String F_CT = "ct";

    private final LocationCryptoProperties properties;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, SecretKeySpec> keys = new HashMap<>();

    public PayloadCipher(LocationCryptoProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 설정을 읽어 키를 세운다.
     *
     * 켜 두었는데 키가 없거나 길이가 틀리면 여기서 기동을 멈춘다. 암호화를
     * 켰다고 믿는데 실제로는 평문으로 쌓이는 상태가 가장 나쁘기 때문이다.
     * 꺼 둔 경우에도 적힌 키는 읽어 둔다 — 이미 봉투로 저장된 값을 계속
     * 읽어야 한다.
     */
    @PostConstruct
    public void init() {
        for (String entry : properties.getKeys().split(",")) {
            String pair = entry.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int sep = pair.indexOf(':');
            if (sep <= 0) {
                throw new IllegalStateException(
                        "location.crypto.keys entry must be '<kid>:<base64 key>'");
            }
            String kid = pair.substring(0, sep).trim();
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(pair.substring(sep + 1).trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("location.crypto key '" + kid + "' is not base64");
            }
            if (raw.length != KEY_BYTES) {
                // 32글자 문자열을 그대로 붙여 넣는 실수가 흔하다. 그 값은 24바이트로
                // 풀리므로 여기서 걸린다.
                throw new IllegalStateException(
                        "location.crypto key '" + kid + "' must decode to "
                                + KEY_BYTES + " bytes, got " + raw.length);
            }
            keys.put(kid, new SecretKeySpec(raw, "AES"));
        }
        if (properties.isEnabled()) {
            if (keys.isEmpty()) {
                throw new IllegalStateException(
                        "location.crypto.enabled=true requires location.crypto.keys");
            }
            if (!keys.containsKey(properties.getActiveKid())) {
                throw new IllegalStateException(
                        "location.crypto.active-kid is not present in location.crypto.keys");
            }
        }
    }

    /**
     * 암호문을 묶어 둘 자리 이름을 만든다.
     *
     * 형식을 한 곳에서만 만드는 이유는, 쓸 때와 읽을 때 한 글자라도 다르면
     * 복호가 실패하기 때문이다. 호출부마다 문자열을 이어붙이면 그 어긋남이
     * 배포 후에야 드러난다.
     *
     * store: 저장소 이름.
     * field: 저장 자리 이름.
     * ref: 행을 지목하는 값. 행이 사는 동안 바뀌지 않는 것만 넣는다.
     */
    public static String aad(String store, String field, String ref) {
        return "map|" + store + "|" + field + "|" + (ref == null ? "none" : ref);
    }

    /** 새로 쓰는 값을 봉투로 감쌀지 여부. */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 본문을 봉투 JSON 문자열로 감싼다. 꺼져 있으면 받은 값을 그대로 돌려준다.
     *
     * plaintext: 감쌀 본문.
     * aad: 이 암호문이 놓일 자리를 지목하는 값.
     */
    public String encrypt(String plaintext, String aad) {
        if (plaintext == null || !properties.isEnabled()) {
            return plaintext;
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        String kid = properties.getActiveKid();
        byte[] sealed = run(Cipher.ENCRYPT_MODE, keys.get(kid), iv, aad,
                plaintext.getBytes(StandardCharsets.UTF_8));

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put(F_VERSION, VERSION);
        envelope.put(F_KID, kid);
        envelope.put(F_IV, Base64.getEncoder().encodeToString(iv));
        envelope.put(F_CT, Base64.getEncoder().encodeToString(sealed));
        return envelope.toString();
    }

    /**
     * 봉투면 풀고, 봉투가 아니면 그대로 돌려준다.
     *
     * value: 저장소에서 읽은 값.
     * aad: 감쌀 때 쓴 것과 같은 값.
     */
    public String decrypt(String value, String aad) {
        if (value == null) {
            return null;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(value);
        } catch (Exception e) {
            return value;
        }
        if (!isEnvelope(node)) {
            return value;
        }
        return new String(open(node, aad), StandardCharsets.UTF_8);
    }

    /** JSON 본문을 봉투 노드로 감싼다. 꺼져 있으면 받은 노드를 그대로 돌려준다. */
    public JsonNode encryptNode(JsonNode plain, String aad) {
        if (plain == null || !properties.isEnabled()) {
            return plain;
        }
        try {
            return objectMapper.readTree(encrypt(objectMapper.writeValueAsString(plain), aad));
        } catch (Exception e) {
            throw new IllegalStateException("failed to seal payload", e);
        }
    }

    /** 봉투 노드면 풀고, 아니면 그대로 돌려준다. */
    public JsonNode decryptNode(JsonNode value, String aad) {
        if (!isEnvelope(value)) {
            return value;
        }
        try {
            return objectMapper.readTree(open(value, aad));
        } catch (Exception e) {
            throw new IllegalStateException("failed to open payload", e);
        }
    }

    /** 봉투의 모양을 갖췄는지 본다. 네 자리가 모두 있어야 봉투로 친다. */
    public boolean isEnvelope(JsonNode node) {
        return node != null && node.isObject()
                && node.hasNonNull(F_VERSION) && node.hasNonNull(F_KID)
                && node.hasNonNull(F_IV) && node.hasNonNull(F_CT);
    }

    private byte[] open(JsonNode envelope, String aad) {
        String kid = envelope.get(F_KID).asText();
        SecretKeySpec key = keys.get(kid);
        if (key == null) {
            // 이 키를 버린 뒤에는 그 키로 쓴 값을 되살릴 방법이 없다. 조용히
            // 넘기면 빈 결과가 정상처럼 보이므로 여기서 멈춘다.
            throw new IllegalStateException("no location.crypto key for kid '" + kid + "'");
        }
        return run(Cipher.DECRYPT_MODE, key,
                Base64.getDecoder().decode(envelope.get(F_IV).asText()), aad,
                Base64.getDecoder().decode(envelope.get(F_CT).asText()));
    }

    private byte[] run(int mode, SecretKeySpec key, byte[] iv, String aad, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null && !aad.isEmpty()) {
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }
            return cipher.doFinal(input);
        } catch (Exception e) {
            throw new IllegalStateException("payload cipher failed", e);
        }
    }
}

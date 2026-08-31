package map.service.user.global.crypto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import map.service.user.global.config.LocationWireProperties;
import org.springframework.stereotype.Component;

/**
 * LocationSeal — 다른 서비스로 보낼 좌표를 봉투에 담는다
 *
 * 좌표는 그동안 요청 줄에 그대로 실려 나갔다. 컨테이너 사이라 바깥에서
 * 들여다볼 수는 없지만, 같은 망에 붙은 다른 컨테이너와 중간에 남는 기록에는
 * 그대로 드러난다. 봉투로 감싸면 그 두 자리에서 사라진다.
 *
 * 봉투를 만들 수 있다는 것 자체가 자격이 된다. 열쇠를 가진 서비스만 만들 수
 * 있고 열쇠를 가진 서비스만 열 수 있으므로, 좌표를 쓰려면 서비스 인증을 먼저
 * 거쳐야 한다는 조건이 형식 자체로 강제된다.
 *
 * 형식은 마침표로 이은 세 조각이다.
 *   v1.&lt;난수&gt;.&lt;암호문+검증표&gt;
 * 두 조각 모두 URL 안전 base64 이고 채움문자를 뗀다 — 요청 줄에 그대로 실려야
 * 하는데 채움문자와 더하기 기호는 주소에서 다른 뜻으로 읽힌다.
 *
 * 만든 시각을 함께 담는다. 좌표만 담으면 한 번 지나간 봉투가 영원히 유효해서,
 * 주워 둔 것을 나중에 그대로 다시 보낼 수 있다.
 *
 * hub 의 location_seal 과 같은 형식을 쓴다. 한쪽만 바꾸면 그 순간부터 모든
 * 좌표 요청이 거절되므로 형식을 바꿀 때는 반드시 함께 바꾼다.
 */
@Component
public class LocationSeal {

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final String VERSION = "v1";
    private static final byte[] AAD = "map|loc|v1".getBytes(StandardCharsets.UTF_8);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final LocationWireProperties properties;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    private SecretKeySpec key;

    public LocationSeal(LocationWireProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 열쇠를 세운다.
     *
     * 감싸기를 켜 두었는데 열쇠가 없거나 길이가 틀리면 기동을 멈춘다.
     * 감쌌다고 믿는데 실제로는 평문이 나가는 상태가 가장 나쁘다.
     */
    @PostConstruct
    public void init() {
        String raw = properties.getKey();
        if (!properties.isEnabled()) {
            return;
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "location.wire.enabled=true requires location.wire.key");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("location.wire.key is not base64");
        }
        if (decoded.length != KEY_BYTES) {
            // 32글자 문자열을 그대로 붙여 넣는 실수가 흔하다. 24바이트로 풀린다.
            throw new IllegalStateException(
                    "location.wire.key must decode to " + KEY_BYTES
                            + " bytes, got " + decoded.length);
        }
        key = new SecretKeySpec(decoded, "AES");
    }

    /** 좌표를 감싸 보낼지 여부. 꺼 두면 예전처럼 값을 그대로 보낸다. */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /** 위도·경도 한 쌍을 감싼다. */
    public String seal(double lat, double lng) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lat", lat);
        payload.put("lng", lng);
        return seal(payload);
    }

    /** 출발·도착처럼 좌표가 둘인 경우를 감싼다. */
    public String sealPair(double startLat, double startLng,
                           double endLat, double endLng) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("start_lat", startLat);
        payload.put("start_lng", startLng);
        payload.put("end_lat", endLat);
        payload.put("end_lng", endLng);
        return seal(payload);
    }

    /**
     * 임의의 값 묶음을 감싼다. 만든 시각을 여기서 한 번만 붙여, 호출부마다
     * 빠뜨릴 여지를 없앤다.
     */
    public String seal(Map<String, Object> payload) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("location.wire is disabled");
        }
        Map<String, Object> body = new LinkedHashMap<>(payload);
        body.put("iat", Instant.now().getEpochSecond());
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD);
            byte[] sealed = cipher.doFinal(
                    objectMapper.writeValueAsBytes(body));
            return VERSION + "." + encoder.encodeToString(iv)
                    + "." + encoder.encodeToString(sealed);
        } catch (Exception e) {
            throw new IllegalStateException("failed to seal location", e);
        }
    }
}

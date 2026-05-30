package map.service.user.recommend.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Mobility — 이동수단 enum
 *
 * 추천 요청/응답에서 이동수단 키워드를 표현한다.
 * JSON 직렬화 시 enum 이름이 아닌 소문자 코드 문자열(walk/bicycle/car/transit)로 매핑된다.
 *
 * 상수:
 * - WALK("walk"): 도보
 * - BICYCLE("bicycle"): 자전거
 * - CAR("car"): 자동차
 * - TRANSIT("transit"): 대중교통
 *
 * value: 외부 노출용 소문자 코드. @JsonValue 로 직렬화된다.
 */
public enum Mobility {
    WALK("walk"),
    BICYCLE("bicycle"),
    CAR("car"),
    TRANSIT("transit");

    private final String value;

    Mobility(String value) {
        this.value = value;
    }

    /**
     * 외부 노출용 소문자 코드 반환.
     *
     * @JsonValue 이므로 JSON 직렬화 시 이 값이 사용된다.
     */
    @JsonValue
    public String value() {
        return value;
    }

    /**
     * 소문자 코드 문자열을 enum 으로 역직렬화.
     *
     * @JsonCreator. raw 가 null 이면 null 을, 일치하는 상수가 없으면 IllegalArgumentException 을 던진다.
     *
     * raw: 소문자 코드 문자열(walk/bicycle/car/transit).
     */
    @JsonCreator
    public static Mobility from(String raw) {
        if (raw == null) {
            return null;
        }
        for (Mobility m : values()) {
            if (m.value.equals(raw)) {
                return m;
            }
        }
        throw new IllegalArgumentException("Unknown mobility: " + raw);
    }
}

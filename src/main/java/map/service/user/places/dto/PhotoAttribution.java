package map.service.user.places.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * PhotoAttribution — 장소 사진 제공자 표기 한 건 (client 계약)
 *
 * hub /v1/places/photos 의 표기 항목을 그대로 받아 client 로 전달한다.
 * 필드명은 hub 응답 키와 일치시킨다.
 *
 * 이 값은 장식이 아니라 사진을 쓰기 위한 조건이다. 사진을 화면에 올리는
 * 쪽은 제공자를 함께 보여야 하므로 중간 단계에서 떨어뜨리지 않는다.
 *
 * displayName: 제공자 이름.
 * uri: 제공자 프로필 URL. 없을 수 있다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PhotoAttribution(
        @JsonProperty("display_name") String displayName,
        String uri
) {
}

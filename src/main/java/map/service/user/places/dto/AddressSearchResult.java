package map.service.user.places.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AddressSearchResult — 주소 검색 결과 한 건 (client 계약)
 *
 * address: 지번 주소.
 * roadAddress: 도로명 주소. 없는 주소도 있어 빈 문자열이 온다.
 *
 * 좌표는 담지 않는다 — 주소를 고르는 화면은 문자열만 쓴다.
 */
public record AddressSearchResult(
        String address,
        @JsonProperty("road_address") String roadAddress
) {
}

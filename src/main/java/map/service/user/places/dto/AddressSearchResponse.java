package map.service.user.places.dto;

import java.util.List;

/**
 * AddressSearchResponse — 주소 검색 응답 본문 (client 계약)
 *
 * hub /v1/places/address 응답을 그대로 받아 client 로 전달한다.
 *
 * addresses: 주소 후보 목록.
 * count: addresses 길이.
 */
public record AddressSearchResponse(
        List<AddressSearchResult> addresses,
        int count
) {
}

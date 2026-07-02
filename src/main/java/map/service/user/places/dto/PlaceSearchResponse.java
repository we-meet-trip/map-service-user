package map.service.user.places.dto;

import java.util.List;
import java.util.Map;

/**
 * PlaceSearchResponse — 장소 검색 응답 본문 (client 계약)
 *
 * hub /v1/places 응답을 그대로 받아 client 로 전달한다.
 *
 * places: 검색된 장소 목록.
 * count: places 길이.
 * sources: 출처별 건수(예: {"kakao": 5, "durunubi": 2}).
 */
public record PlaceSearchResponse(
        List<PlaceSearchResult> places,
        int count,
        Map<String, Integer> sources
) {
}

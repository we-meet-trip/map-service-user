package map.service.user.places.dto;

import java.util.List;

/**
 * ReviewSearchResponse — 리뷰 검색 응답 본문 (client 계약)
 *
 * hub /v1/reviews 응답을 그대로 받아 client 로 전달한다.
 *
 * query: 검색에 사용된 질의어.
 * reviews: 검색된 리뷰 목록.
 * count: reviews 길이.
 */
public record ReviewSearchResponse(
        String query,
        List<ReviewItem> reviews,
        int count
) {
}

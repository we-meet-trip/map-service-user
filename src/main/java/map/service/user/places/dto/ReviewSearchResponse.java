package map.service.user.places.dto;

import java.util.List;

/**
 * ReviewSearchResponse — 리뷰 검색 응답 본문 (client 계약)
 *
 * hub /v1/reviews 응답을 그대로 받아 client 로 전달한다.
 *
 * query: 검색에 사용된 질의어.
 * reviews: 검색된 리뷰 목록.
 * count: reviews 길이. 요청한 display 보다 작으면 그 구간이 마지막이다 —
 *        호출 측은 이 값으로 더보기를 멈춘다.
 * start: 이 응답이 담은 구간의 시작 위치.
 */
public record ReviewSearchResponse(
        String query,
        List<ReviewItem> reviews,
        int count,
        int start
) {
}

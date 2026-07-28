package map.service.user.places.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * ReviewItem — 리뷰(블로그) 검색 결과 한 건 (client 계약)
 *
 * hub /v1/reviews 의 리뷰 항목을 그대로 받아 client 로 전달한다. 필드명은 hub
 * 응답 키(네이버 블로그 검색 스키마)와 일치한다.
 *
 * title: 리뷰 제목(검색 강조 태그 포함 가능).
 * description: 리뷰 요약 발췌.
 * bloggername: 블로거 이름.
 * postdate: 작성일(yyyyMMdd).
 * link: 리뷰 원문 URL.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewItem(
        String title,
        String description,
        String bloggername,
        String postdate,
        String link
) {
}

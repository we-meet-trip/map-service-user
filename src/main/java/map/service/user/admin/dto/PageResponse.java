package map.service.user.admin.dto;

import java.util.List;

/**
 * PageResponse — 관리자 목록 조회 공통 페이지 응답
 *
 * Spring Data Page 를 admin 콘솔이 소비하기 쉬운 최소 형태로 감싼다(엔티티
 * 직렬화 금지, DTO 만 담는다).
 *
 * items         : 현재 페이지 항목(DTO 리스트).
 * page          : 0-based 페이지 인덱스.
 * size          : 페이지 크기.
 * totalElements : 전체 건수.
 * totalPages    : 전체 페이지 수.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}

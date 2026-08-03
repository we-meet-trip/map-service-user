package map.service.user.places.dto;

import java.util.List;

/**
 * ReviewSummaryResponse — 장소 블로그 요약 응답 (client 계약)
 *
 * 장소를 눌렀을 때 보여줄 두 줄 요약을 담는다.
 *
 * query: 요약에 사용한 검색어(= 장소명). 어떤 이름으로 찾은 결과인지
 *        화면이 구분할 수 있게 되돌려준다.
 * bullets: 요약 줄. 근거가 부족하거나 모델이 형식을 벗어나면 빈 목록으로
 *          온다 — 화면은 이때 요약 영역을 접는다.
 * sourceCount: 요약 근거로 쓴 블로그 글 수. 0 이면 근거를 하나도 못 구한
 *              상태다.
 */
public record ReviewSummaryResponse(
        String query,
        List<String> bullets,
        int sourceCount
) {
}

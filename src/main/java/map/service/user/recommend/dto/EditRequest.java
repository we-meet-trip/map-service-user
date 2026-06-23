package map.service.user.recommend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * EditRequest — 추천 draft 부분 수정 요청 본문
 *
 * RecommendController.edit (/api/v1/recommend/{jobId}/edit) 의 @RequestBody.
 * 모든 필드는 nullable 이며, null 이 아닌 필드만 RecommendService.applyEdit 에서
 * 기존 draft JSON 의 동일 키를 덮어쓰는 shallow merge 대상이 된다.
 *
 * places: 장소 목록 갱신 데이터. null 이면 미수정.
 * visitOrder: 방문 순서 갱신 데이터. JSON key "visit_order". null 이면 미수정.
 * legs: 이동 구간 갱신 데이터. null 이면 미수정.
 */
public record EditRequest(
        List<Place> places,
        @JsonProperty("visit_order") List<Integer> visitOrder,
        List<Leg> legs
) {
}

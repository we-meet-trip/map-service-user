package map.service.user.recommend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Place — 추천 응답/수정의 장소 항목
 *
 * RecommendResponse.places 와 EditRequest.places 의 원소 타입.
 * 외부 장소 식별자, 표시 정보, 좌표, 권장 체류 시간을 담는다.
 *
 * placeId: 외부 장소 식별자(int). JSON key "place_id".
 * name: 장소명.
 * address: 주소 문자열.
 * lat: 위도.
 * lng: 경도.
 * recommendedVisitTime: 권장 체류 시간 문자열. JSON key "recommended_visit_time".
 *
 * 아래는 실측 출처가 채우는 보강 필드(없을 수 있어 모두 nullable). 추천
 * 결과를 stops 로 접을 때 출처/분류/신뢰 표시를 client 로 전달하는 데 쓴다.
 * contentId: 출처 접두사를 붙인 식별자. JSON key "content_id".
 * source: 출처 구분("kakao" | "durunubi").
 * category: 분류 텍스트.
 * grounded: 실측 후보에 근거한 장소면 true, LLM 단독 생성이면 false.
 * reason: agent llm_reason 노드가 생성한 장소별 추천 이유(≤200자).
 *         degrade(생성 생략) 시 null 일 수 있다.
 */
public record Place(
        @JsonProperty("place_id") int placeId,
        String name,
        String address,
        double lat,
        double lng,
        @JsonProperty("recommended_visit_time") String recommendedVisitTime,
        @JsonProperty("content_id") String contentId,
        String source,
        String category,
        Boolean grounded,
        String reason
) {
}

package map.service.user.recommend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Place — 추천 응답/수정의 장소 항목
 *
 * RecommendResponse.places 와 EditRequest.places 의 원소 타입.
 * 외부 장소 식별자, 표시 정보, 좌표, 권장 체류 시간을 담는다.
 *
 * placeId: 외부 장소 식별자(int). JSON key "place_id".
 * name: 장소명.
 * address: 주소 문자열.
 * lat: 위도. 33.0~43.0(한국 국내 범위). EditRequest 로 들어오는 수정
 *      요청에서만 @Valid cascade 로 검증된다 — 범위 값은 agent Place
 *      (33~43 / 124~132)·hub DirectionsPoint 와 통일한다. 아웃바운드
 *      RecommendResponse 는 Bean Validation 을 거치지 않으므로 영향 없다.
 * lng: 경도. 124.0~132.0(한국 국내 범위).
 * recommendedVisitTime: 권장 체류 시간 문자열. JSON key "recommended_visit_time".
 *
 * 아래는 실측 출처가 채우는 보강 필드(없을 수 있어 모두 nullable). 추천
 * 결과를 stops 로 접을 때 출처/분류/신뢰 표시를 client 로 전달하는 데 쓴다.
 * contentId: 출처 접두사를 붙인 식별자. JSON key "content_id".
 * source: 출처 구분("kakao" | "durunubi").
 * category: 분류 텍스트.
 * grounded: 실측 후보에 근거한 장소면 true, LLM 단독 생성이면 false.
 * placeUrl: 출처 서비스의 장소 상세 페이지 링크. JSON key "place_url".
 * reason: agent llm_reason 노드가 생성한 장소별 추천 이유(≤200자).
 *         degrade(생성 생략) 시 null 일 수 있다.
 * bullets: agent summarize_reviews 노드가 블로그 후기를 종합한 요약 2줄.
 *          근거가 될 후기를 못 구한 장소나 degrade 시 null 이다.
 *
 * placeUrl/reason/bullets 의 크기 제약은 수정 요청(EditRequest)으로 들어오는
 * 값에만 적용된다. 이 레코드는 draft 를 그대로 실어 나르는 통로이자 수정
 * 본문의 원소이기도 해서, 제약이 없으면 클라이언트가 임의 길이·임의 개수를
 * draft 에 영구히 심을 수 있다. 아웃바운드(추천 결과)는 Bean Validation 을
 * 거치지 않으므로 영향이 없다.
 */
public record Place(
        @JsonProperty("place_id") int placeId,
        String name,
        String address,
        @DecimalMin("33.0") @DecimalMax("43.0") double lat,
        @DecimalMin("124.0") @DecimalMax("132.0") double lng,
        @JsonProperty("recommended_visit_time") String recommendedVisitTime,
        @JsonProperty("content_id") String contentId,
        String source,
        String category,
        Boolean grounded,
        @JsonProperty("place_url") @Size(max = 500) String placeUrl,
        @Size(max = 200) String reason,
        @Size(max = 2) List<@Size(max = 80) String> bullets
) {
}

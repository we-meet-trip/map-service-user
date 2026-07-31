package map.service.user.trip.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * TripStop — 일정의 한 방문지
 *
 * TripGenerateResponse.stops 의 원소. agent 의 places + visit_order 를 접어
 * 만들며, 구간 이동은 transportToNext 로 임베드한다(client 계약).
 * 모든 필드는 non-null 보장(client 의 엄격 캐스팅 크래시 방지).
 *
 * order: 방문 순서(1부터). visit_order 의 index 기반.
 * day: 여행 일차(1부터). places 의 day 를 그대로 전달하며, client 는
 *      이 값으로 일차별 탭·지도 경로를 그린다.
 * name/address: places 의 name/address.
 * time: 방문 시각 "HH:mm". TripMapping 이 해당 day 안에서 활동 시간대
 *       기준으로 균등 계산(R-2, day 별로 독립 계산됨).
 * latitude/longitude: places 의 lat/lng.
 * transportToNext: 다음 stop 으로의 이동. 마지막 stop 이거나 다음 stop 이
 *                  다른 day 인 경우 null 이며, null 인 경우 직렬화에서
 *                  키를 생략한다(client 는 null 허용).
 * source: 장소 출처("kakao" | "durunubi"). 없으면 키 생략.
 * category: 분류 텍스트. 없으면 키 생략.
 * grounded: 실측 후보에 근거한 장소면 true, LLM 단독 생성이면 false.
 *           없으면 키 생략(저신뢰 신호로 client 가 활용).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TripStop(
        int order,
        int day,
        String name,
        String address,
        String time,
        double latitude,
        double longitude,
        @JsonProperty("transport_to_next") TransportToNext transportToNext,
        String source,
        String category,
        Boolean grounded
) {
}

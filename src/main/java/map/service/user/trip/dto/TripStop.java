package map.service.user.trip.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * TripStop — 일정의 한 방문지
 *
 * TripGenerateResponse.stops 의 원소. agent 의 places + visit_order 를 접어
 * 만들며, 구간 이동은 transportToNext 로 임베드한다(client 계약).
 * order/name/address/time/latitude/longitude 는 항상 채운다(client 가
 * 엄격 캐스팅으로 읽어 null 이면 크래시한다). 나머지는 nullable 이며
 * NON_NULL 직렬화로 키가 빠지므로 client 는 부재를 전제로 읽어야 한다.
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
 *
 * 아래 4개는 agent draft 가 이미 갖고 있었으나 stops 로 접을 때 버려지던
 * 값이다. 전부 nullable 이고 NON_NULL 직렬화라 값이 없으면 키 자체가
 * 빠지므로, 기존 client 의 파싱을 깨지 않고 덧붙는다.
 * placeId: agent 가 부여한 장소 식별자. JSON key "place_id".
 *          장소 상세 조회·선택 장소 재요청에서 장소를 지목하는 키다.
 * placeUrl: 출처 서비스의 장소 상세 페이지 링크. JSON key "place_url".
 * reason: 이 장소를 추천한 이유(≤200자).
 * bullets: 블로그 후기를 종합한 요약 2줄. 근거가 될 후기를 못 구한 장소는
 *          이 키가 없다 — client 는 없을 때를 전제로 그려야 한다.
 *
 * 아래 둘은 agent 가 시간축을 세운 일정에만 있다. time 이 "언제 도착하나"라면
 * 이 둘은 "언제까지 얼마나 머무나"를 말한다. 시간축 없이 만들어진 일정에는
 * 값이 없어 키가 빠지므로, 있을 때만 그리면 된다.
 * endTime: 그 장소를 떠나는 시각("HH:MM"). JSON key "end_time".
 * stayMinutes: 머무는 시간(분). JSON key "stay_minutes".
 *
 * contentId: 출처 접두사가 붙은 장소 식별자("kakao:123"). JSON key "content_id".
 *            placeId 가 한 번의 추천 안에서만 유효한 번호인 반면 이 값은 추천을
 *            가로질러 같은 장소를 가리킨다. 재탐색이 "이 장소들 말고 다른 곳"을
 *            요구할 때 client 가 지목하는 키다. LLM 이 지어낸 장소는 이 값이
 *            없어 키가 빠진다.
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
        Boolean grounded,
        @JsonProperty("place_id") Integer placeId,
        @JsonProperty("place_url") String placeUrl,
        String reason,
        List<String> bullets,
        @JsonProperty("end_time") String endTime,
        @JsonProperty("stay_minutes") Integer stayMinutes,
        @JsonProperty("content_id") String contentId
) {
}

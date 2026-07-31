package map.service.user.trip.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TripStopJsonTest — 방문지 직렬화 검증(추가 필드 포함/생략)
 *
 * place_id/place_url/reason/bullets 는 agent draft 가 주는 값이라 없을 수
 * 있다. 값이 없으면 키 자체를 생략해(@JsonInclude NON_NULL) 이 필드들을
 * 모르는 기존 client 의 응답 파싱이 그대로 동작해야 한다.
 */
@DisplayName("TripStop 직렬화")
class TripStopJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static TripStop stop(
            Integer placeId, String placeUrl, String reason,
            List<String> bullets
    ) {
        return new TripStop(
                1, "속초해변", "강원특별자치도 속초시", "09:00",
                38.19, 128.60, null, "kakao", "관광", true,
                placeId, placeUrl, reason, bullets);
    }

    @Test
    @DisplayName("추가 필드 포함 — snake_case 키로 직렬화")
    void serializesAdditiveFields() throws Exception {
        String json = mapper.writeValueAsString(stop(
                7, "http://place.map.kakao.com/7", "바다 전망이 좋아서",
                List.of("백사장이 넓고 산책하기 좋다는 후기가 많음",
                        "주말 오후에는 주차가 붐빈다는 언급 다수")));

        assertThat(json).contains("\"place_id\":7");
        assertThat(json).contains("\"place_url\":\"http://place.map.kakao.com/7\"");
        assertThat(json).contains("\"reason\":\"바다 전망이 좋아서\"");
        assertThat(json).contains("\"bullets\":[");
        assertThat(json).contains("백사장이 넓고");
    }

    @Test
    @DisplayName("추가 필드 null — 키 자체를 생략")
    void omitsAdditiveFieldsWhenNull() throws Exception {
        String json = mapper.writeValueAsString(stop(null, null, null, null));

        assertThat(json).doesNotContain("place_id");
        assertThat(json).doesNotContain("place_url");
        assertThat(json).doesNotContain("reason");
        assertThat(json).doesNotContain("bullets");
        // 필수 필드는 그대로 남는다.
        assertThat(json).contains("\"order\":1");
        assertThat(json).contains("\"name\":\"속초해변\"");
        assertThat(json).contains("\"time\":\"09:00\"");
    }
}

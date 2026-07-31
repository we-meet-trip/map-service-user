package map.service.user.recommend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PlaceJsonTest — agent draft 의 장소 항목 역직렬화 검증
 *
 * draft 는 agent 가 만든 JSON 을 그대로 저장·전달한 것이라, 이 레코드가
 * 모르는 필드(phone, crs_* 등)가 항상 섞여 있다. 애플리케이션은
 * fail-on-unknown-properties=false 로 그것들을 무시하도록 설정돼 있으므로
 * 본 테스트도 같은 설정으로 파싱해 실제 경로와 동일한 조건을 만든다.
 */
@DisplayName("Place 역직렬화(agent draft)")
class PlaceJsonTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    @DisplayName("bullets/place_url/reason 을 읽고 모르는 필드는 무시")
    void readsAdditiveFieldsAndIgnoresUnknown() throws Exception {
        String json = """
                {
                  "place_id": 3,
                  "name": "속초해변",
                  "address": "강원특별자치도 속초시",
                  "lat": 38.19,
                  "lng": 128.60,
                  "recommended_visit_time": "오전 10시",
                  "content_id": "kakao:123",
                  "source": "kakao",
                  "category": "여행 > 관광",
                  "grounded": true,
                  "place_url": "http://place.map.kakao.com/123",
                  "reason": "바다 전망이 좋아서",
                  "bullets": ["백사장이 넓다는 후기", "주말 오후 주차 혼잡"],
                  "phone": "033-000-0000",
                  "crs_dstnc_km": 1.2,
                  "category_group_code": "AT4"
                }
                """;

        Place p = mapper.readValue(json, Place.class);

        assertThat(p.placeId()).isEqualTo(3);
        assertThat(p.placeUrl()).isEqualTo("http://place.map.kakao.com/123");
        assertThat(p.reason()).isEqualTo("바다 전망이 좋아서");
        assertThat(p.bullets())
                .containsExactly("백사장이 넓다는 후기", "주말 오후 주차 혼잡");
    }

    @Test
    @DisplayName("요약이 없는 장소는 bullets 가 null")
    void bulletsAbsentStaysNull() throws Exception {
        String json = """
                {
                  "place_id": 0,
                  "name": "장소",
                  "address": "주소",
                  "lat": 37.5,
                  "lng": 127.0,
                  "recommended_visit_time": "오전"
                }
                """;

        Place p = mapper.readValue(json, Place.class);

        assertThat(p.bullets()).isNull();
        assertThat(p.reason()).isNull();
        assertThat(p.placeUrl()).isNull();
    }
}

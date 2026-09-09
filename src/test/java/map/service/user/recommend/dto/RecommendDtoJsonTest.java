package map.service.user.recommend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

/**
 * RecommendDtoJsonTest — agent 확장 페이로드(reason/clothing)의 매핑 검증
 *
 * agent JobDonePayload 확장 필드가 dto 로 역직렬화되는지, 미포함(과거
 * 페이로드)이어도 하위호환으로 null 매핑되는지 확인한다.
 */
class RecommendDtoJsonTest {

    // 운영에서는 Spring 자동구성 ObjectMapper(JavaTimeModule 포함)를 쓴다 —
    // 단위 테스트도 동일하게 LocalDate/LocalTime 직렬화를 등록한다.
    private final ObjectMapper mapper =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void deserializesReasonAndClothing() throws Exception {
        String json = """
                {"job_id":"j1","status":"done",
                 "places":[{"place_id":0,"name":"장소","address":"주소",
                   "lat":37.5,"lng":127.0,"recommended_visit_time":"오전",
                   "content_id":"kakao:1","source":"kakao",
                   "category":"카페","grounded":true,"reason":"조용한 분위기"}],
                 "visit_order":[0],
                 "legs":[],
                 "clothing":"가벼운 겉옷과 우산",
                 "error":null}""";

        RecommendResponse response =
                mapper.readValue(json, RecommendResponse.class);

        assertThat(response.clothing()).isEqualTo("가벼운 겉옷과 우산");
        assertThat(response.places()).hasSize(1);
        assertThat(response.places().get(0).reason())
                .isEqualTo("조용한 분위기");
        assertThat(response.places().get(0).grounded()).isTrue();
    }

    @Test
    void toleratesPayloadWithoutNewFields() throws Exception {
        // 구 버전 agent 페이로드(reason/clothing 부재) 하위호환
        String json = """
                {"job_id":"j2","status":"done",
                 "places":[{"place_id":0,"name":"장소","address":"주소",
                   "lat":37.5,"lng":127.0,"recommended_visit_time":"오전"}],
                 "visit_order":[0],
                 "legs":[]}""";

        RecommendResponse response =
                mapper.readValue(json, RecommendResponse.class);

        assertThat(response.clothing()).isNull();
        assertThat(response.places().get(0).reason()).isNull();
        assertThat(response.warnings()).isNull();
        assertThat(response.timelineStatus()).isNull();
        assertThat(response.code()).isNull();
        assertThat(response.retryable()).isNull();
    }

    @Test
    void successJsonPreservesEveryLegacyFieldWithNullableFailureMetadata() throws Exception {
        String legacy = """
                {"job_id":"j-success","status":"done","places":[],"visit_order":[],"legs":[],
                 "clothing":"가벼운 겉옷","error":null,"retry_after_seconds":null,
                 "warnings":["날씨 미확인"],"timeline_status":"unverified"}""";
        RecommendResponse response = mapper.readValue(legacy, RecommendResponse.class);
        var actual = mapper.readTree(mapper.writeValueAsString(response));
        var expected = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(legacy);
        expected.putNull("code");
        expected.putNull("retryable");

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void terminalFailureJsonExposesExactCodeAndBooleanWithoutReplacingLegacyError() throws Exception {
        String payload = """
                {"job_id":"j-failed","status":"failed","places":null,"visit_order":null,"legs":null,
                 "clothing":null,"error":"장소 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.",
                 "retry_after_seconds":null,"warnings":null,"timeline_status":null,
                 "code":"upstream_unavailable","retryable":true}""";
        RecommendResponse response = mapper.readValue(payload, RecommendResponse.class);

        assertThat(response.code()).isEqualTo("upstream_unavailable");
        assertThat(response.retryable()).isTrue();
        assertThat(mapper.readTree(mapper.writeValueAsString(response)))
                .isEqualTo(mapper.readTree(payload));
    }

    @Test
    void deserializesWarningsAndTimelineStatus() throws Exception {
        String json = """
                {"job_id":"j3","status":"done",
                 "places":[],
                 "visit_order":[],
                 "legs":[],
                 "timeline_status":"trimmed",
                 "warnings":["날씨 정보를 확인하지 못해 일정에 반영하지 못했습니다",
                             "하루 활동 시간에 맞춰 일부 일정을 줄였습니다"]}""";

        RecommendResponse response =
                mapper.readValue(json, RecommendResponse.class);

        assertThat(response.timelineStatus()).isEqualTo("trimmed");
        assertThat(response.warnings()).hasSize(2);
        assertThat(response.warnings().get(0)).contains("날씨");
    }

    @Test
    void serializesRequestWithStageAndExclude() throws Exception {
        String json = mapper.writeValueAsString(new RecommendRequest(
                new DateRange(
                        java.time.LocalDate.of(2026, 7, 6),
                        java.time.LocalDate.of(2026, 7, 6),
                        java.time.LocalTime.of(9, 0),
                        java.time.LocalTime.of(18, 0)),
                null,
                null,
                null,
                "서울특별시",
                "강남구",
                "sched-1",
                "mode1",
                java.util.List.of("kakao:1"),
                null));

        assertThat(json).contains("\"schedule_id\":\"sched-1\"");
        assertThat(json).contains("\"stage\":\"mode1\"");
        assertThat(json).contains("\"exclude\":[\"kakao:1\"]");
    }
}

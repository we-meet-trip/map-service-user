package map.service.user.trip.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TripGenerateResponseJsonTest — 생성 응답 직렬화 검증(warnings 포함/생략)
 *
 * warnings/timeline_status 는 draft 가 주는 값이라 없을 수 있다. 값이 없으면
 * 키 자체를 생략해, 이 필드들을 모르는 기존 client 의 응답 파싱이 그대로
 * 동작해야 한다. 값이 있으면 사용자 안내 문장이 그대로 실려야 한다.
 */
@DisplayName("TripGenerateResponse 직렬화")
class TripGenerateResponseJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("warnings 존재 — 문장 목록과 timeline_status 를 그대로 싣는다")
    void serializesWarnings() throws Exception {
        String json = mapper.writeValueAsString(new TripGenerateResponse(
                "job-1", 30, List.of(), List.of(),
                List.of("날씨 정보를 확인하지 못해 일정에 반영하지 못했습니다"),
                "unverified"));

        assertThat(json).contains("\"warnings\":[\"날씨 정보를 확인하지 못해");
        assertThat(json).contains("\"timeline_status\":\"unverified\"");
    }

    @Test
    @DisplayName("warnings null — 키 자체를 생략")
    void omitsWarningsWhenNull() throws Exception {
        String json = mapper.writeValueAsString(new TripGenerateResponse(
                "job-1", 30, List.of(), List.of(), null, null));

        assertThat(json).doesNotContain("warnings");
        assertThat(json).doesNotContain("timeline_status");
        assertThat(json).contains("\"trip_id\":\"job-1\"");
    }
}

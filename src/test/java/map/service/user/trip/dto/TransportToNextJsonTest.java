package map.service.user.trip.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TransportToNextJsonTest — 이동 카드 직렬화 검증(path 포함/생략)
 *
 * duration_minutes/distance_km snake_case 키와, path 가 null 이면 키 자체를
 * 생략(@JsonInclude NON_NULL)해 client 가 직선 폴백하도록 하는 계약을 확인한다.
 */
@DisplayName("TransportToNext 직렬화")
class TransportToNextJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("path 포함 — snake_case 키로 직렬화")
    void serializesWithPath() throws Exception {
        TransportToNext t = new TransportToNext(
                "walk", "이동: 도보", 21, 1.42,
                List.of(List.of(37.57, 126.97), List.of(37.58, 126.98)));

        String json = mapper.writeValueAsString(t);

        assertThat(json).contains("\"duration_minutes\":21");
        assertThat(json).contains("\"distance_km\":1.42");
        assertThat(json).contains("\"path\":[[37.57,126.97],[37.58,126.98]]");
    }

    @Test
    @DisplayName("path null — 키 자체를 생략")
    void omitsPathWhenNull() throws Exception {
        TransportToNext t =
                new TransportToNext("bus", "이동: 버스", 12, 3.4, null);

        String json = mapper.writeValueAsString(t);

        assertThat(json).doesNotContain("path");
        assertThat(json).contains("\"duration_minutes\":12");
        assertThat(json).contains("\"distance_km\":3.4");
    }
}

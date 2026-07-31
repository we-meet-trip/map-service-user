package map.service.user.trip;

import static org.assertj.core.api.Assertions.assertThat;

import map.service.user.recommend.dto.Mobility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TripMappingKickboardTest — client transport → agent Mobility 매핑 회귀 테스트
 *
 * scooter 를 BICYCLE 로 치환하던 시절에는 hub 룰이 자전거 반경 10km 를 적용해
 * SoT(def §3.6)가 정한 킥보드 7km 가 한 번도 쓰이지 않았다.
 */
@DisplayName("TripMapping.toAgentMobility 킥보드 분리")
class TripMappingKickboardTest {

    @Test
    @DisplayName("scooter 는 BICYCLE 이 아니라 SCOOTER 로 매핑된다")
    void scooterMapsToScooter() {
        assertThat(TripMapping.toAgentMobility("scooter")).isEqualTo(Mobility.SCOOTER);
        assertThat(TripMapping.toAgentMobility("SCOOTER")).isEqualTo(Mobility.SCOOTER);
    }

    @Test
    @DisplayName("나머지 이동수단 매핑은 그대로 유지된다")
    void otherTransportsUnchanged() {
        assertThat(TripMapping.toAgentMobility("walk")).isEqualTo(Mobility.WALK);
        assertThat(TripMapping.toAgentMobility("bicycle")).isEqualTo(Mobility.BICYCLE);
        assertThat(TripMapping.toAgentMobility("bus")).isEqualTo(Mobility.TRANSIT);
    }

    @Test
    @DisplayName("SCOOTER 는 소문자 코드 scooter 로 직렬화된다")
    void scooterSerializesAsLowercaseCode() {
        assertThat(Mobility.SCOOTER.value()).isEqualTo("scooter");
        assertThat(Mobility.from("scooter")).isEqualTo(Mobility.SCOOTER);
    }
}

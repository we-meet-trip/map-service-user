package map.service.user.trip;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import map.service.user.trip.dto.HubDirectionsDtos.Route;
import map.service.user.trip.dto.TransportToNext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TripStopsAssemblerMeasuredTest — withMeasured 실측 대체 규칙 단위 테스트
 *
 * route 가 있으면 이동 카드의 시간/거리를 OSRM 실측으로 대체하고 path 를
 * 부착하며, null 이면 기저(LLM 추정) 카드를 그대로 둔다.
 */
@DisplayName("TripStopsAssembler.withMeasured 실측 대체")
class TripStopsAssemblerMeasuredTest {

    private static TransportToNext base() {
        // LLM 추정 기저 카드(시간 9분/거리 0.9km, path 없음).
        return new TransportToNext("walk", "이동: 도보", 9, 0.9, null);
    }

    @Test
    @DisplayName("route 실측값으로 시간/거리 대체 + path 부착")
    void replacesWithMeasured() {
        List<List<Double>> path =
                List.of(List.of(37.5, 127.0), List.of(37.6, 127.1));
        Route route = new Route(path, 1420, 1230);

        TransportToNext out = TripStopsAssembler.withMeasured(base(), route);

        assertThat(out.type()).isEqualTo("walk");
        assertThat(out.label()).isEqualTo("이동: 도보");
        assertThat(out.durationMinutes()).isEqualTo(21); // ceil(1230/60)
        assertThat(out.distanceKm()).isEqualTo(1.42); // round(1420/10)/100
        assertThat(out.path()).isEqualTo(path);
    }

    @Test
    @DisplayName("짧은 구간도 최소 1분 보장")
    void clampsDurationToAtLeastOneMinute() {
        Route route = new Route(List.of(List.of(37.5, 127.0)), 40, 30);

        TransportToNext out = TripStopsAssembler.withMeasured(base(), route);

        assertThat(out.durationMinutes()).isEqualTo(1); // max(1, ceil(30/60))
        assertThat(out.distanceKm()).isEqualTo(0.04); // round(40/10)/100
    }

    @Test
    @DisplayName("route null 이면 기저 카드 불변(LLM 추정 유지, path 없음)")
    void keepsBaseWhenRouteNull() {
        TransportToNext out = TripStopsAssembler.withMeasured(base(), null);

        assertThat(out.durationMinutes()).isEqualTo(9);
        assertThat(out.distanceKm()).isEqualTo(0.9);
        assertThat(out.path()).isNull();
    }

    @Test
    @DisplayName("킥보드(scooter)는 실측 duration 에 0.7 보정을 적용한다")
    void appliesKickboardDurationFactor() {
        // OSRM 은 킥보드 전용 프로파일이 없어 bicycle 값을 돌려준다. SoT def §3.5
        // 가 정한 0.7 보정을 BFF 가 적용해야 카드에 킥보드 속도가 반영된다.
        Route route = new Route(List.of(List.of(37.5, 127.0)), 1420, 1200);

        TransportToNext out = TripStopsAssembler.withMeasured(base(), route, "scooter");

        // ceil(1200 * 0.7 / 60) = ceil(14.0) = 14
        assertThat(out.durationMinutes()).isEqualTo(14);
        // 거리와 경로는 보정하지 않는다(같은 도로를 지난다).
        assertThat(out.distanceKm()).isEqualTo(1.42);
    }

    @Test
    @DisplayName("킥보드가 아니면 보정하지 않는다")
    void doesNotApplyFactorForOtherTransports() {
        Route route = new Route(List.of(List.of(37.5, 127.0)), 1420, 1200);

        assertThat(TripStopsAssembler.withMeasured(base(), route, "bicycle")
                .durationMinutes()).isEqualTo(20); // ceil(1200/60)
        assertThat(TripStopsAssembler.withMeasured(base(), route, "walk")
                .durationMinutes()).isEqualTo(20);
        assertThat(TripStopsAssembler.withMeasured(base(), route, null)
                .durationMinutes()).isEqualTo(20);
    }
}

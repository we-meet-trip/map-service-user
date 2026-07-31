package map.service.user.trip;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import map.service.user.recommend.dto.Leg;
import map.service.user.recommend.dto.Mobility;
import map.service.user.recommend.dto.Place;
import map.service.user.recommend.dto.RecommendResponse;
import map.service.user.trip.dto.BudgetRange;
import map.service.user.trip.dto.Location;
import map.service.user.trip.dto.Schedule;
import map.service.user.trip.dto.TripGenerateRequest;
import map.service.user.trip.dto.TripStop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TripServiceDayBoundaryTest — stops 를 일차 단위로 접는 규칙 단위 테스트
 *
 * 방문 시각은 일차마다 처음부터 다시 배분되고, 일차가 바뀌는 지점에는
 * 이동 카드를 달지 않는다. 일차 값이 비어 들어온 경우 1일차로 접는다.
 *
 * transport 를 "bus" 로 두어 도로 경로 조회 경로를 타지 않게 한다 —
 * 이동수단이 라우팅 대상이 아니면 hub 를 부르지 않으므로 협력 객체가
 * 없어도 stops 조립만 따로 검증할 수 있다.
 */
@DisplayName("TripService.toStops 일차 경계 처리")
class TripServiceDayBoundaryTest {

    private static final TripService SERVICE =
            new TripService(null, null, null, null, null, 120, 700);

    private static Place place(int id, int day) {
        return new Place(
                id, day, "장소" + id, "주소" + id,
                37.5 + id * 0.01, 127.0 + id * 0.01,
                "오전", null, null, null, null, null);
    }

    private static Leg leg(int from, int to) {
        return new Leg(from, to, Mobility.TRANSIT, 1.2, 15);
    }

    private static Schedule schedule() {
        return new Schedule(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), 9, 18);
    }

    private static TripGenerateRequest request() {
        return new TripGenerateRequest(
                schedule(), new BudgetRange(0, 100000),
                List.of("food"), "bus",
                new Location("강원특별자치도", "속초시"));
    }

    private static List<TripStop> stopsOf(List<Place> places) {
        RecommendResponse result = new RecommendResponse(
                "job-1", "done", places,
                List.of(0, 1, 2, 3),
                List.of(leg(0, 1), leg(1, 2), leg(2, 3)),
                null, null, null);
        return SERVICE.toStops(request(), result, schedule());
    }

    @Test
    @DisplayName("일차가 바뀌는 지점에는 이동 카드를 달지 않는다")
    void dropsTransportAtDayBoundary() {
        List<TripStop> stops = stopsOf(List.of(
                place(0, 1), place(1, 1), place(2, 2), place(3, 2)));

        assertThat(stops).extracting(TripStop::day)
                .containsExactly(1, 1, 2, 2);
        assertThat(stops.get(0).transportToNext()).isNotNull();
        // 1일차 마지막 → 2일차 첫 장소로 넘어가는 지점.
        assertThat(stops.get(1).transportToNext()).isNull();
        assertThat(stops.get(2).transportToNext()).isNotNull();
        // 마지막 stop 은 다음 구간 자체가 없다.
        assertThat(stops.get(3).transportToNext()).isNull();
    }

    @Test
    @DisplayName("방문 시각은 일차마다 처음부터 다시 배분된다")
    void restartsStopTimePerDay() {
        List<TripStop> stops = stopsOf(List.of(
                place(0, 1), place(1, 1), place(2, 2), place(3, 2)));

        // 각 일차의 첫 stop 끼리, 둘째 stop 끼리 같은 시각이어야 한다.
        assertThat(stops.get(2).time()).isEqualTo(stops.get(0).time());
        assertThat(stops.get(3).time()).isEqualTo(stops.get(1).time());
        assertThat(stops.get(1).time()).isNotEqualTo(stops.get(0).time());
    }

    @Test
    @DisplayName("일차 값이 비어 들어오면 전부 1일차로 접는다")
    void foldsMissingDayIntoFirstDay() {
        // day 가 붙기 전 형식으로 남아 있는 payload 는 역직렬화 기본값 0 이 된다.
        List<TripStop> stops = stopsOf(List.of(
                place(0, 0), place(1, 0), place(2, 0), place(3, 0)));

        assertThat(stops).extracting(TripStop::day)
                .containsExactly(1, 1, 1, 1);
        // 하나의 일차이므로 경계가 없어 마지막을 뺀 전 구간에 이동 카드가 붙는다.
        assertThat(stops.get(0).transportToNext()).isNotNull();
        assertThat(stops.get(1).transportToNext()).isNotNull();
        assertThat(stops.get(2).transportToNext()).isNotNull();
        assertThat(stops.get(3).transportToNext()).isNull();
    }
}

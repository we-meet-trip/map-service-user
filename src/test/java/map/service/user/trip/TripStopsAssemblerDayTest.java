package map.service.user.trip;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import map.service.user.recommend.dto.Leg;
import map.service.user.recommend.dto.Mobility;
import map.service.user.recommend.dto.Place;
import map.service.user.trip.dto.TripStop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TripStopsAssemblerDayTest — stops 를 일차 단위로 접는 규칙 단위 테스트
 *
 * 방문 시각은 일차마다 처음부터 다시 배분되고, 일차가 바뀌는 지점에는
 * 이동 카드를 달지 않는다. 일차 값이 비어 들어온 경우 1일차로 접는다.
 *
 * routes 를 null 로 넘겨 도로 경로 조회 없이 조립만 검증한다.
 */
@DisplayName("TripStopsAssembler.toStops 일차 경계 처리")
class TripStopsAssemblerDayTest {

    private static Place place(int id, int day) {
        return new Place(
                id, day, "장소" + id, "주소" + id,
                37.5 + id * 0.01, 127.0 + id * 0.01,
                "오전", null, null, null, true, null, null, null,
                null, null, null);
    }

    private static Leg leg(int from, int to) {
        return new Leg(from, to, Mobility.WALK, 1.2, 15);
    }

    private static List<TripStop> stopsOf(List<Place> ordered) {
        return TripStopsAssembler.toStops(
                ordered,
                List.of(leg(0, 1), leg(1, 2), leg(2, 3)),
                "walk", 9, 18, null);
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
        // 일차가 붙기 전 형식으로 남아 있는 payload 는 역직렬화 기본값 0 이 된다.
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

    // ── 시간축 이중 경로 ──────────────────────────────────────

    /** agent 가 계산한 방문 시각을 실은 장소. */
    private static Place timedPlace(
            int id, int day, String start, String end, int stay
    ) {
        return new Place(
                id, day, "장소" + id, "주소" + id,
                37.5 + id * 0.01, 127.0 + id * 0.01,
                "오전", null, null, null, true, null, null, null,
                stay, start, end);
    }

    @Test
    @DisplayName("계산된 방문 시각이 있으면 균등 분할 대신 그 값을 쓴다")
    void prefersComputedVisitTimes() {
        List<TripStop> stops = TripStopsAssembler.toStops(
                List.of(
                        timedPlace(0, 1, "09:00", "10:00", 60),
                        timedPlace(1, 1, "10:30", "11:15", 45),
                        timedPlace(2, 1, "11:40", "12:40", 60)),
                List.of(leg(0, 1), leg(1, 2)),
                "walk", 9, 18, null);

        assertThat(stops).extracting(TripStop::time)
                .containsExactly("09:00", "10:30", "11:40");
        assertThat(stops).extracting(TripStop::endTime)
                .containsExactly("10:00", "11:15", "12:40");
        assertThat(stops).extracting(TripStop::stayMinutes)
                .containsExactly(60, 45, 60);
    }

    @Test
    @DisplayName("계산된 시각이 없으면 예전처럼 활동 시간대를 균등 분할한다")
    void fallsBackToEvenSplitWithoutComputedTimes() {
        List<TripStop> stops = stopsOf(List.of(
                place(0, 1), place(1, 1), place(2, 1)));

        // 09:00~18:00(540분)을 2등분 → 09:00 / 13:30 / 18:00
        assertThat(stops).extracting(TripStop::time)
                .containsExactly("09:00", "13:30", "18:00");
        // 시간축이 없는 일정에는 이 키들이 아예 없다.
        assertThat(stops).extracting(TripStop::endTime)
                .containsOnlyNulls();
        assertThat(stops).extracting(TripStop::stayMinutes)
                .containsOnlyNulls();
    }
}

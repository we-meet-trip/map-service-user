package map.service.user.trip;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import map.service.user.trip.dto.TripStop;
import map.service.user.trip.dto.TransportToNext;
import org.junit.jupiter.api.Test;

class TripTimelineGateTest {
    TripStop stop(int day, String begin, String end, Integer travel) {
        return new TripStop(1, day, "real", "address", begin, 37.5, 127.0,
                travel == null ? null : new TransportToNext("walk", "walk", travel, 1, null),
                "kakao", "place", true, 1, null, null, null, end, 60, "kakao:1");
    }
    @Test void finalMeasuredTravelCannotOverrunNextVisit() {
        assertThatThrownBy(() -> TripStopsAssembler.requireTimelineConsistency(
                List.of(stop(1,"09:00","10:00",31), stop(1,"10:30","11:30",null)),9,18))
                .isInstanceOf(TripTimelineException.class);
    }
    @Test void unchangedFeasibleWindowsArePreservedAndDayBoundaryIsNotALeg() {
        var stops=List.of(stop(1,"09:00","10:00",30),stop(1,"10:30","11:30",null),
                stop(2,"09:00","10:00",null));
        assertThatCode(() -> TripStopsAssembler.requireTimelineConsistency(stops,9,18)).doesNotThrowAnyException();
        assertThat(stops.get(1).time()).isEqualTo("10:30");
    }
    @Test void activityOverrunPartialOrMalformedTimelineRequiresRevalidation() {
        for (var stops : List.of(List.of(stop(1,"17:00","18:01",null)),
                List.of(stop(1,"09:00","10:00",30),stop(1,"10:30",null,null)),
                List.of(stop(1,"09:00","25:00",null)))) {
            assertThatThrownBy(() -> TripStopsAssembler.requireTimelineConsistency(stops,9,18))
                    .isInstanceOf(TripTimelineException.class);
        }
    }
}

package map.service.user.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import map.service.user.recommend.dto.Leg;
import map.service.user.recommend.dto.Mobility;
import map.service.user.recommend.dto.Place;
import map.service.user.recommend.dto.RecommendResponse;
import map.service.user.trip.dto.HubDirectionsDtos.Route;
import map.service.user.trip.dto.TripStop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 이동수단 표기가 달라도 같은 결과가 나오는지 본다.
 *
 * 예전에는 소문자만 도로 경로를 받았다. 대문자로 적어 보내면 요청은 200 이고
 * 일정도 멀쩡히 나오는데 경로만 조용히 직선이 됐다. 두 표기가 서로 다른
 * 결과를 내면서 아무 말도 하지 않으니, 화면을 열어 보기 전에는 알 수 없었다.
 */
@DisplayName("TripStopsAssembler 이동수단 표기")
class TripStopsAssemblerModeCaseTest {

    private static Place place(int id) {
        return new Place(id, 1, "장소" + id, "주소", 37.5 + id * 0.01, 127.0,
                "10:00", null, null, null, true, null, null, null,
                null, null, null);
    }

    private static RecommendResponse draft() {
        return new RecommendResponse(
                "job-1", "done",
                List.of(place(0), place(1)),
                List.of(0, 1),
                List.of(new Leg(0, 1, Mobility.WALK, 0.9, 9)),
                null, null, null, null, null);
    }

    private static Route route() {
        return new Route(
                List.of(List.of(37.50, 127.0), List.of(37.505, 127.0),
                        List.of(37.51, 127.0)),
                1420, 1230, "OSRM", "foot");
    }

    @Test
    @DisplayName("대문자로 적어도 도로 경로를 받아 온다")
    void uppercaseStillRoutes() {
        HubDirectionsClient client = mock(HubDirectionsClient.class);
        when(client.fetchRoutes(eq("walk"), anyList())).thenReturn(List.of(route()));

        List<TripStop> stops =
                new TripStopsAssembler(client).assemble(draft(), "WALK", 10, 18);

        // 받는 쪽은 소문자만 받으므로 넘기는 값도 맞춰져 있어야 한다.
        verify(client).fetchRoutes(eq("walk"), anyList());
        assertThat(stops.get(0).transportToNext().path()).hasSize(3);
    }

    @Test
    @DisplayName("버스는 여전히 도로 경로를 묻지 않는다")
    void busIsStillNotRouted() {
        HubDirectionsClient client = mock(HubDirectionsClient.class);

        new TripStopsAssembler(client).assemble(draft(), "BUS", 10, 18);

        verify(client, never()).fetchRoutes(org.mockito.ArgumentMatchers.anyString(), anyList());
    }
}

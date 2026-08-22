package map.service.user.trip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import map.service.user.recommend.dto.Leg;
import map.service.user.recommend.dto.Place;
import map.service.user.recommend.dto.RecommendResponse;
import map.service.user.trip.dto.HubDirectionsDtos.LegReq;
import map.service.user.trip.dto.HubDirectionsDtos.Point;
import map.service.user.trip.dto.HubDirectionsDtos.Route;
import map.service.user.trip.dto.TransportToNext;
import map.service.user.trip.dto.TripStop;
import org.springframework.stereotype.Component;

/**
 * TripStopsAssembler — 추천 결과(draft)를 client 의 stops[] 로 접는다.
 *
 * 추천 결과는 장소 목록(places) · 방문 순서(visit_order) · 구간(legs) 세 조각으로
 * 흩어져 있다. client 는 "순서대로 늘어선 방문지, 각 방문지에 다음 지점까지의
 * 이동 카드가 붙은" 하나의 배열을 기대한다. 그 접기를 여기서 한다.
 *
 * 같은 접기를 두 곳이 쓴다.
 *  - 생성 직후 응답(TripService)
 *  - 저장된 일정 상세 조회(ScheduleService)
 * 두 화면이 같은 방문 시각·같은 이동 카드·같은 폴리라인을 보여야 하므로
 * 구현을 하나로 둔다.
 *
 * 이동 시간/거리는 두 단계로 만든다. 먼저 추천 결과에 담긴 추정치로 카드를
 * 세우고, 도로 경로 조회에 성공한 구간만 실측값으로 갈아끼운다. 조회가
 * 실패하거나 대상이 아닌 이동수단이면 추정치가 그대로 남고 폴리라인이 없어
 * client 가 직선으로 그린다.
 */
@Component
public class TripStopsAssembler {

    /**
     * 킥보드 소요시간 보정 계수.
     *
     * 킥보드 전용 라우팅 프로파일이 없어 자전거 프로파일로 경로를 받는다.
     * 그대로 쓰면 자전거 기준 소요시간이 나오므로 이 계수를 곱해 킥보드
     * 속도로 환산한다. 거리와 폴리라인은 같은 도로를 지나므로 보정하지 않는다.
     */
    private static final double KICKBOARD_DURATION_FACTOR = 0.7;

    /** 활동 시간대를 모르는 일정을 조립할 때 쓰는 시작 시각. */
    public static final int DEFAULT_START_HOUR = 9;
    /** 활동 시간대를 모르는 일정을 조립할 때 쓰는 종료 시각. */
    public static final int DEFAULT_END_HOUR = 21;

    private final HubDirectionsClient hubDirectionsClient;

    public TripStopsAssembler(HubDirectionsClient hubDirectionsClient) {
        this.hubDirectionsClient = hubDirectionsClient;
    }

    /**
     * 추천 결과를 stops[] 로 접는다.
     *
     * result: 추천 draft. places/visit_order 가 비어 있으면
     *         TripGenerateException 을 던진다(접을 것이 없다).
     * transport: 이동수단. null 이면 도로 경로를 조회하지 않고 이동 카드의
     *            라벨도 수단 없는 표기가 된다.
     * startHour/endHour: 활동 시간대. 방문 시각을 이 구간에 균등 배치한다.
     */
    public List<TripStop> assemble(
            RecommendResponse result, String transport, int startHour, int endHour
    ) {
        List<Place> ordered = orderPlaces(result);
        List<Route> routes = fetchRoutes(transport, ordered);
        return toStops(ordered, result.legs(), transport, startHour, endHour, routes);
    }

    /**
     * visit_order 를 따라 Place 를 늘어놓는다.
     *
     * 순서에 있는 place_id 가 places 에 없으면 그 방문지를 그릴 수 없으므로
     * 부분 결과를 내지 않고 실패시킨다.
     */
    static List<Place> orderPlaces(RecommendResponse result) {
        List<Place> places = result.places();
        List<Integer> order = result.visitOrder();
        if (places == null || places.isEmpty() || order == null || order.isEmpty()) {
            throw new TripGenerationException("recommendation has no places");
        }
        Map<Integer, Place> byId = new HashMap<>();
        for (Place p : places) {
            byId.put(p.placeId(), p);
        }
        List<Place> ordered = new ArrayList<>(order.size());
        for (Integer id : order) {
            Place p = byId.get(id);
            if (p == null) {
                throw new TripGenerationException(
                        "place_id " + id + " missing in places");
            }
            ordered.add(p);
        }
        return ordered;
    }

    /**
     * 순서가 확정된 장소들을 stops[] 로 만든다.
     *
     * 이동 카드는 마지막 방문지를 제외한 각 방문지에 붙으며, legs 가 모자라면
     * 그만큼 카드 없이 남는다. routes 는 legs 와 같은 인덱스로 대응하고 원소가
     * null 인 구간은 추정치를 유지한다.
     *
     * 방문 시각은 일차마다 처음부터 다시 배분한다. 여러 날 일정을 한 줄로
     * 이어 배분하면 하루 시간대에 모든 날의 방문지가 뭉쳐 나온다. 그리고
     * 일차가 바뀌는 지점에는 이동 카드를 달지 않는다 — 다음 날 첫 방문지로
     * 이어지는 이동은 일정에 표시하지 않는다. 경계 구간의 route 는 만들지
     * 않는 게 아니라 쓰지 않는 것이라, routes 와 legs 의 인덱스 대응은
     * 그대로 유지된다.
     *
     * agent 가 visit_order 를 일차 오름차순으로 묶어 돌려주고 서버 측에서도
     * 재검증하므로, 여기서는 순서를 그대로 믿고 일차가 바뀌는 지점만 본다.
     */
    static List<TripStop> toStops(
            List<Place> ordered,
            List<Leg> legs,
            String transport,
            int startHour,
            int endHour,
            List<Route> routes
    ) {
        int n = ordered.size();
        String label = TripMapping.transportLabel(transport);

        // 일차별 방문지 수 — 방문 시각을 그 날 안에서 나누는 분모다.
        Map<Integer, Integer> dayTotals = new HashMap<>();
        for (Place p : ordered) {
            dayTotals.merge(dayOf(p), 1, Integer::sum);
        }

        Map<Integer, Integer> dayRunningIndex = new HashMap<>();
        List<TripStop> stops = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Place p = ordered.get(i);
            int day = dayOf(p);
            int indexInDay = dayRunningIndex.merge(day, 1, Integer::sum) - 1;
            int totalInDay = dayTotals.get(day);

            TransportToNext toNext = null;
            if (i < n - 1 && legs != null && i < legs.size()
                    && dayOf(ordered.get(i + 1)) == day) {
                Leg leg = legs.get(i);
                TransportToNext base = new TransportToNext(
                        transport, label,
                        leg.estimatedDurationMin(),
                        leg.estimatedDistanceKm(),
                        null);
                Route route = (routes != null && i < routes.size())
                        ? routes.get(i) : null;
                toNext = withMeasured(base, route, transport);
            }
            // agent 가 시간축을 세웠으면 그 시각을 쓴다. 그쪽은 이동시간과
            // 체류시간을 쌓아 만든 값이라, 활동 시간대를 방문지 수로 나누는
            // 아래 폴백보다 실제 일정에 가깝다. 값이 없는 일정(시간축 도입
            // 전에 저장된 것)은 폴백이 받아 예전과 똑같은 화면을 낸다.
            String time = hasText(p.visitStart())
                    ? p.visitStart()
                    : TripMapping.stopTime(
                            startHour, endHour, indexInDay, totalInDay);

            stops.add(new TripStop(
                    i + 1,
                    day,
                    p.name(),
                    p.address(),
                    time,
                    p.lat(),
                    p.lng(),
                    toNext,
                    p.source(),
                    p.category(),
                    p.grounded(),
                    p.placeId(),
                    p.placeUrl(),
                    p.reason(),
                    p.bullets(),
                    hasText(p.visitEnd()) ? p.visitEnd() : null,
                    p.stayMinutes()));
        }
        return stops;
    }

    /** 값이 있고 공백만은 아닌지. 시간축 필드의 유무 판정에 쓴다. */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 방문 일차를 읽는다 — 값이 없거나 1 미만이면 1일차로 본다.
     *
     * 일차가 붙기 전에 만들어져 아직 남아 있는 드래프트·재사용 캐시를 읽으면
     * 역직렬화 기본값 0 이 들어오는데, 그대로 두면 client 가 "0일차" 탭을
     * 그리게 된다. 여기서 한 번만 보정해 그런 payload 도 1일차로 접는다.
     */
    private static int dayOf(Place p) {
        return Math.max(1, p.day());
    }

    /** stops 의 이동 카드 시간 합. 마지막 방문지는 카드가 없어 합산에서 빠진다. */
    public static int totalDurationMinutes(List<TripStop> stops) {
        int total = 0;
        for (TripStop s : stops) {
            if (s.transportToNext() != null) {
                total += s.transportToNext().durationMinutes();
            }
        }
        return total;
    }

    /**
     * 이동 구간의 도로 추종 경로를 hub 에 일괄 요청한다.
     *
     * 라우팅 대상이 아닌 이동수단(bus/transit)이거나 구간이 없으면 호출하지
     * 않고 null 을 돌려준다(전 구간 직선 폴백).
     *
     * 날짜가 바뀌는 자리는 묻지 않는다. 그 자리에는 이동 카드가 붙지 않아
     * (toStops 가 같은 날일 때만 카드를 만든다) 경로를 받아도 쓸 데가 없고,
     * 하루의 마지막 방문지에서 다음 날 첫 방문지까지를 걸어가는 경로로 그리면
     * 화면에도 어긋난다. 물어보지 않은 자리는 아래에서 다시 비워, 돌려주는
     * 목록의 인덱스는 구간 번호와 그대로 맞춘다.
     *
     * @return routes(구간 수와 같은 길이·인덱스, 경로가 없는 구간은 원소가 null),
     *         또는 미호출·전량 실패 시 null.
     */
    private List<Route> fetchRoutes(String transport, List<Place> ordered) {
        if (ordered.size() < 2 || !isRoutable(transport)) {
            return null;
        }
        int legCount = ordered.size() - 1;
        List<LegReq> legs = new ArrayList<>(legCount);
        int[] legIndex = new int[legCount];
        for (int i = 0; i < legCount; i++) {
            Place a = ordered.get(i);
            Place b = ordered.get(i + 1);
            if (dayOf(a) != dayOf(b)) {
                continue;
            }
            legIndex[legs.size()] = i;
            legs.add(new LegReq(
                    new Point(a.lat(), a.lng()),
                    new Point(b.lat(), b.lng()),
                    legName(a.name()),
                    legName(b.name())));
        }
        if (legs.isEmpty()) {
            return null;
        }

        List<Route> fetched = hubDirectionsClient.fetchRoutes(normalizeMode(transport), legs);
        if (fetched == null) {
            return null;
        }
        List<Route> byLeg = new ArrayList<>(Collections.nCopies(legCount, null));
        for (int i = 0; i < legs.size() && i < fetched.size(); i++) {
            byLeg.set(legIndex[i], fetched.get(i));
        }
        return byLeg;
    }

    /**
     * 도로 라우팅 가능한 이동수단인지. walk/bicycle/scooter 만 대상(bus 제외).
     *
     * 대소문자를 가리지 않는다. 예전에는 소문자만 받았는데, 대문자로 적어
     * 보내면 요청이 200 으로 돌아오고 일정도 멀쩡히 나오면서 경로만 조용히
     * 직선이 됐다. 두 표기가 서로 다른 결과를 내면서 아무 것도 알려 주지
     * 않는 셈이라, 무엇이 잘못됐는지 화면을 보기 전까지 알 수 없었다.
     * 캐시 키는 이미 표기를 하나로 맞춰 다루므로 여기만 어긋나 있었다.
     */
    private static boolean isRoutable(String transport) {
        String mode = normalizeMode(transport);
        return "walk".equals(mode)
                || "bicycle".equals(mode)
                || "scooter".equals(mode);
    }

    /** 받는 쪽(hub)은 소문자만 받는다. 넘기기 전에 표기를 맞춘다. */
    private static String normalizeMode(String transport) {
        return transport == null ? null : transport.trim().toLowerCase(Locale.ROOT);
    }

    /** hub 계약(start_name/goal_name: 1~60자)에 맞게 장소명을 정리한다. */
    private static String legName(String name) {
        if (name == null || name.isBlank()) {
            return "지점";
        }
        String trimmed = name.strip();
        return trimmed.length() > 60 ? trimmed.substring(0, 60) : trimmed;
    }

    /**
     * 경로 조회에 성공한 구간의 이동 카드를 실측값으로 대체한다.
     *
     * route 가 null(구간 실패/미조회)이면 기저 카드(추정치)를 그대로 둔다.
     * 성공 시 시간=올림(초→분, 최소 1분), 거리=반올림(m→km, 소수 2자리),
     * path=도로 폴리라인을 부착한다.
     */
    static TransportToNext withMeasured(TransportToNext base, Route route) {
        return withMeasured(base, route, null);
    }

    /**
     * transport 를 함께 받아 킥보드 소요시간 보정까지 적용하는 변형.
     */
    static TransportToNext withMeasured(
            TransportToNext base, Route route, String transport) {
        if (route == null) {
            return base;
        }
        double durationS = route.durationS();
        if ("scooter".equalsIgnoreCase(transport == null ? "" : transport.trim())) {
            durationS *= KICKBOARD_DURATION_FACTOR;
        }
        int durationMinutes = Math.max(1, (int) Math.ceil(durationS / 60.0));
        double distanceKm = Math.round(route.distanceM() / 10.0) / 100.0;
        return new TransportToNext(
                base.type(), base.label(), durationMinutes, distanceKm, route.path());
    }
}

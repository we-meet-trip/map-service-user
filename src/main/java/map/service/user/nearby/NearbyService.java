package map.service.user.nearby;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import map.service.user.schedule.ScheduleEntity;
import map.service.user.schedule.ScheduleService;

/**
 * 일정의 한 방문지 주변에서 묵을 곳·먹을 곳·카페를 찾아 준다.
 *
 * <p>보여 준 것과 눌린 것을 함께 남긴다. 눌린 것만 남기면 "안 눌렀다" 가
 * "안 보였다" 인지 "보고 안 골랐다" 인지 구분되지 않아, 반례로 쓸 수 없다.
 *
 * <p>기록에 실패해도 목록은 그대로 준다. 화면에 보여 주는 것이 본래 일이고
 * 남기는 것은 곁가지라, 곁가지 때문에 본래 일이 막히면 안 된다.
 */
@Service
public class NearbyService {

    private static final Logger log = LoggerFactory.getLogger(NearbyService.class);

    /** 바깥에서 받는 분류. 발급처 코드는 hub 안쪽에만 있다. */
    private static final Set<String> CATEGORIES = Set.of("stay", "food", "cafe");

    private final ScheduleService scheduleService;
    private final HubNearbyClient hubClient;
    private final NearbyImpressionRepository impressionRepository;
    private final int radiusMeters;
    private final int size;

    public NearbyService(
            ScheduleService scheduleService,
            HubNearbyClient hubClient,
            NearbyImpressionRepository impressionRepository,
            @Value("${nearby.radius-meters:1000}") int radiusMeters,
            @Value("${nearby.size:10}") int size) {
        this.scheduleService = scheduleService;
        this.hubClient = hubClient;
        this.impressionRepository = impressionRepository;
        this.radiusMeters = radiusMeters;
        this.size = size;
    }

    /** 받을 수 있는 분류인지. 라우트가 400 을 낼지 가리는 데 쓴다. */
    public static boolean isKnownCategory(String category) {
        return category != null && CATEGORIES.contains(category);
    }

    /**
     * 지정한 방문지 주변을 찾아 주고, 보여 준 것을 남긴다.
     *
     * <p>주인이 아니면 404 다. 남의 일정 주변을 열어 주면 그 사람이 어디를
     * 가려 했는지가 그대로 드러난다.
     *
     * @param day       여행 일차
     * @param stopOrder 그 날의 몇 번째 방문지인지(1 부터)
     */
    @Transactional
    public List<NearbyPlace> find(Long scheduleId, Long userId,
                                  int day, int stopOrder, String category) {
        ScheduleEntity schedule = scheduleService.requireOwned(scheduleId, userId);
        double[] point = coordinateAt(schedule, day, stopOrder);
        if (point == null) {
            // 일정에 없는 자리를 물었다. 빈 목록이 사실이다.
            return List.of();
        }
        List<NearbyPlace> found =
                hubClient.find(point[0], point[1], category, radiusMeters, size);

        List<NearbyPlace> ranked = new ArrayList<>(found.size());
        for (int i = 0; i < found.size(); i++) {
            NearbyPlace p = found.get(i);
            ranked.add(new NearbyPlace(p.contentId(), p.name(), p.address(),
                    p.lat(), p.lng(), p.category(), i, p.placeUrl()));
        }
        recordImpressions(scheduleId, day, stopOrder, category, ranked);
        return ranked;
    }

    /**
     * 주변 장소를 눌렀다고 남긴다.
     *
     * <p>보여 준 적 없는 것을 눌렀다고 하면 무시한다. 목록에 없던 것이 눌릴 수
     * 없으므로, 그런 요청은 잘못 온 것이거나 지어낸 것이다.
     *
     * @return 이번에 새로 새겼으면 true
     */
    @Transactional
    public boolean recordClick(Long scheduleId, Long userId, int day, int stopOrder,
                               String category, String contentId) {
        scheduleService.requireOwned(scheduleId, userId);
        return impressionRepository
                .findByScheduleIdAndDayAndStopOrderAndCategoryAndContentId(
                        scheduleId, day, stopOrder, category, contentId)
                .map(row -> {
                    boolean first = row.getClickedAt() == null;
                    row.markClicked(OffsetDateTime.now());
                    impressionRepository.save(row);
                    return first;
                })
                .orElse(false);
    }

    /**
     * 보여 준 목록을 남긴다. 이미 있는 것은 그대로 둔다.
     *
     * <p>같은 자리를 다시 열면 같은 목록이 또 온다. 그때마다 행을 늘리면 한 번
     * 보여 준 것이 여러 번으로 세어지고, 처음 보여 준 때도 사라진다.
     */
    private void recordImpressions(Long scheduleId, int day, int stopOrder,
                                   String category, List<NearbyPlace> places) {
        OffsetDateTime now = OffsetDateTime.now();
        for (NearbyPlace p : places) {
            if (p.contentId() == null) {
                continue;
            }
            try {
                if (impressionRepository
                        .findByScheduleIdAndDayAndStopOrderAndCategoryAndContentId(
                                scheduleId, day, stopOrder, category, p.contentId())
                        .isPresent()) {
                    continue;
                }
                impressionRepository.save(new NearbyImpressionEntity(
                        scheduleId, day, stopOrder, category, p.contentId(),
                        p.rank(), now));
            } catch (DataIntegrityViolationException e) {
                // 같은 목록을 두 번 열었다. 먼저 온 것이 남으면 된다.
                log.debug("nearby impression already recorded content_id={}", p.contentId());
            } catch (RuntimeException e) {
                // 남기지 못해도 목록은 그대로 준다. 화면이 본래 일이다.
                log.warn("nearby impression record failed schedule_id={} cause={}",
                        scheduleId, e.getClass().getSimpleName());
                return;
            }
        }
    }

    /**
     * 일정에서 그 자리의 좌표를 꺼낸다. 없으면 null.
     *
     * <p>순번은 방문 순서대로 1 부터 매겨진다. 도착 기록이 쓰는 것과 같은
     * 규칙이라, 두 신호가 같은 자리를 가리킨다.
     */
    private double[] coordinateAt(ScheduleEntity schedule, int day, int stopOrder) {
        JsonNode payload = schedule.getPayload();
        if (payload == null) {
            return null;
        }
        JsonNode order = payload.path("visit_order");
        JsonNode places = payload.path("places");
        if (!order.isArray() || !places.isArray()
                || stopOrder < 1 || stopOrder > order.size()) {
            return null;
        }
        int placeId = order.get(stopOrder - 1).asInt(-1);
        for (JsonNode p : places) {
            if (p.path("place_id").asInt(-1) != placeId) {
                continue;
            }
            if (p.path("day").asInt(1) != day) {
                return null;
            }
            if (!p.hasNonNull("lat") || !p.hasNonNull("lng")) {
                return null;
            }
            return new double[]{p.get("lat").asDouble(), p.get("lng").asDouble()};
        }
        return null;
    }
}

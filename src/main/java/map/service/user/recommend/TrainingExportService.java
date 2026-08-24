package map.service.user.recommend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.nearby.NearbyImpressionEntity;
import map.service.user.nearby.NearbyImpressionRepository;
import map.service.user.recommend.dto.TrainingExportRow;
import map.service.user.schedule.ScheduleArrivalEntity;
import map.service.user.schedule.ScheduleArrivalRepository;
import map.service.user.schedule.ScheduleEntity;
import map.service.user.schedule.ScheduleExportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 쌓인 세션을 학습에 쓸 수 있는 모양으로 조립한다.
 *
 * <p>한 세션은 "이 조건으로 물었고, 이런 후보가 나왔고, 그중 이것을 저장했고,
 * 실제로 이만큼 갔다" 를 한 덩어리로 묶은 것이다. 그 조각들이 네 표에 흩어져
 * 있어(일정·잡·신호·도착) 잇는 규칙이 이 클래스의 전부다.
 *
 * <h2>어느 잡이 후보를 갖고 있는가</h2>
 * 저장된 일정이 가리키는 잡에 후보가 있으리라는 보장이 없다. 캐시로 답한 잡은
 * 남이 만든 결과를 복사만 했으므로 후보가 없고, 그것을 처음 만든 잡에만 있다.
 * 그래서 <b>자기에게 신호가 있으면 자기가 원본이고, 없을 때만 계보를 한 칸
 * 따라간다.</b>
 *
 * <p>다시 짜기(research)는 예외다. 그쪽의 계보는 "복사해 온 곳" 이 아니라
 * "물려서 버린 앞의 결과" 를 가리킨다. 따라가면 사용자가 거절한 후보로
 * 학습하게 되므로 따라가지 않는다.
 *
 * <h2>같은 것을 여러 번 저장했을 때</h2>
 * 한 벌만 남기고 버리면 안 된다. 실제로 한 벌은 살아 있고 다른 한 벌은 지워진
 * 경우가 있는데, 아무 쪽이나 고르면 "지웠다" 또는 "실제로 갔다" 중 하나가
 * 통째로 사라진다. 묶어서 증거를 합친다. 지웠다는 판정은 <b>전부 지웠을 때만</b>
 * 내린다 — 두 벌 중 한 벌만 지운 것은 거절이 아니라 중복 정리다.
 *
 * <h2>나누기 기준</h2>
 * 배우는 쪽과 재는 쪽을 잡 단위로 가르면 샌다. 뒤에서 같은 조건으로 캐시를 다시
 * 채우며 새 잡이 계속 생기기 때문에, 사실상 같은 세션이 양쪽에 갈린다. 요청
 * 조건 자체를 기준으로 삼는다.
 */
@Service
public class TrainingExportService {

    private static final Logger log = LoggerFactory.getLogger(TrainingExportService.class);

    /** 이 파일 형식의 판. 읽는 쪽이 보고 갈라 읽는다. */
    static final int EXPORT_SCHEMA_VERSION = 1;

    private static final String UNKNOWN = "unknown";

    private final ScheduleExportRepository scheduleExportRepository;
    private final ScheduleArrivalRepository arrivalRepository;
    private final NearbyImpressionRepository impressionRepository;
    private final RecommendJobRepository jobRepository;
    private final RecommendTrainingRepository trainingRepository;
    private final UserRepository userRepository;
    private final RecommendCacheKey cacheKeyBuilder;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public TrainingExportService(
            ScheduleExportRepository scheduleExportRepository,
            ScheduleArrivalRepository arrivalRepository,
            NearbyImpressionRepository impressionRepository,
            RecommendJobRepository jobRepository,
            RecommendTrainingRepository trainingRepository,
            UserRepository userRepository,
            RecommendCacheKey cacheKeyBuilder,
            ObjectMapper objectMapper,
            @Value("${training.export.batch-size:500}") int batchSize) {
        this.scheduleExportRepository = scheduleExportRepository;
        this.arrivalRepository = arrivalRepository;
        this.impressionRepository = impressionRepository;
        this.jobRepository = jobRepository;
        this.trainingRepository = trainingRepository;
        this.userRepository = userRepository;
        this.cacheKeyBuilder = cacheKeyBuilder;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize > 0 ? batchSize : 500;
    }

    /** 한 번 뽑은 결과와, 그 과정에서 무엇이 몇 건 걸러졌는지. */
    public record Result(List<TrainingExportRow> rows, Summary summary) {
    }

    /**
     * 각 단계에서 몇 건이 남았는지.
     *
     * <p>결과가 0행일 때 그것이 고장인지 사실인지 가리려면 이 숫자가 필요하다.
     * 걸러진 이유별 건수 없이 빈 파일만 나오면 사람이 버그로 오해한다.
     */
    public record Summary(int scanned, int jobLinked, int chained, int deduped,
                          int excludedTest, int excludedUnknownUser, int written,
                          int splitKeyFallback) {
    }

    /**
     * 전부 훑어 조립한다.
     *
     * @param excludeTestAccounts 시험용 계정을 뺄지. 켜면 사용자를 모르는 세션도 뺀다 —
     *                            우리 점검 트래픽이 바로 그 모양이라, 모르는 것을 진짜
     *                            사용자로 세면 거르는 쪽이 새는 방향으로 틀린다.
     * @param testEmailDomains    시험용으로 볼 메일 도메인
     * @param salt                식별자를 지문으로 바꿀 때 섞을 소금
     */
    public Result export(boolean excludeTestAccounts,
                         Collection<String> testEmailDomains,
                         String salt) {
        List<ScheduleEntity> all = readAllSchedules();
        List<Session> sessions = chain(all);
        List<Group> groups = dedupe(sessions);

        int excludedTest = 0;
        int excludedUnknownUser = 0;
        List<Group> kept = new ArrayList<>(groups.size());
        for (Group g : groups) {
            if (!excludeTestAccounts) {
                kept.add(g);
                continue;
            }
            if (g.userId() == null) {
                excludedUnknownUser++;
                continue;
            }
            if (isTestAccount(g.userId(), testEmailDomains)) {
                excludedTest++;
                continue;
            }
            kept.add(g);
        }

        String exportedAt = OffsetDateTime.now().toString();
        List<TrainingExportRow> rows = new ArrayList<>(kept.size());
        Map<Long, List<ScheduleArrivalEntity>> arrivals = readArrivals(kept);
        Map<Long, List<NearbyImpressionEntity>> impressions = readImpressions(kept);
        for (Group g : kept) {
            rows.add(toRow(g, arrivals, impressions, exportedAt, salt));
        }

        // 나누기 기준이 조용히 잡 단위로 떨어지면, 같은 조건의 세션이 배우는 쪽과
        // 재는 쪽으로 갈려도 아무도 모른다. 몇 건이 그랬는지 세어 밖으로 낸다.
        int fallback = (int) rows.stream()
                .filter(r -> "origin_job_fallback".equals(r.splitKeySource())).count();
        Summary summary = new Summary(all.size(), all.size(), sessions.size(),
                groups.size(), excludedTest, excludedUnknownUser, rows.size(), fallback);
        return new Result(rows, summary);
    }

    // ── 1) 일정을 전부 읽는다 ─────────────────────────────────────

    private List<ScheduleEntity> readAllSchedules() {
        List<ScheduleEntity> out = new ArrayList<>();
        long after = 0L;
        while (true) {
            List<ScheduleEntity> page = scheduleExportRepository.findPage(after, batchSize);
            if (page.isEmpty()) {
                return out;
            }
            out.addAll(page);
            after = page.get(page.size() - 1).getScheduleId();
            if (page.size() < batchSize) {
                return out;
            }
        }
    }

    // ── 2) 후보를 가진 원본까지 잇는다 ────────────────────────────

    /** 조립이 끝난 한 세션. 아직 묶기 전이다. */
    private record Session(ScheduleEntity schedule, RecommendJobEntity savedJob,
                           UUID originJobId, String originResolution,
                           RecommendJobEntity originJob, JsonNode trainingPayload,
                           Integer trainingSchemaVersion, String savedSetKey) {
    }

    private List<Session> chain(List<ScheduleEntity> schedules) {
        if (schedules.isEmpty()) {
            return List.of();
        }
        Map<UUID, RecommendJobEntity> jobs = byId(jobRepository.findAllById(
                schedules.stream().map(ScheduleEntity::getJobId).collect(Collectors.toSet())));

        // 원본 후보를 한 번에 모아 두 번 물어보지 않는다.
        Set<UUID> lookAt = new HashSet<>();
        for (ScheduleEntity s : schedules) {
            lookAt.add(s.getJobId());
            RecommendJobEntity j = jobs.get(s.getJobId());
            if (j != null && j.getParentJobId() != null) {
                lookAt.add(j.getParentJobId());
            }
        }
        Map<UUID, RecommendTrainingEntity> signals = new HashMap<>();
        for (RecommendTrainingEntity t : trainingRepository.findAllById(lookAt)) {
            signals.put(t.getJobId(), t);
        }
        Map<UUID, RecommendJobEntity> originJobs = byId(jobRepository.findAllById(lookAt));

        List<Session> out = new ArrayList<>();
        for (ScheduleEntity s : schedules) {
            RecommendJobEntity job = jobs.get(s.getJobId());
            if (job == null) {
                continue;
            }
            UUID origin;
            String resolution;
            if (signals.containsKey(s.getJobId())) {
                origin = s.getJobId();
                resolution = "self";
            } else if (!"research".equals(job.getMode()) && job.getParentJobId() != null) {
                origin = job.getParentJobId();
                resolution = "parent";
            } else {
                continue;
            }
            RecommendTrainingEntity signal = signals.get(origin);
            if (signal == null) {
                // 후보가 없으면 학습에 쓸 수 없다. 세지 않고 버린다.
                continue;
            }
            out.add(new Session(s, job, origin, resolution, originJobs.get(origin),
                    signal.getPayload(), signal.getSchemaVersion(), savedSetKey(s)));
        }
        return out;
    }

    private Map<UUID, RecommendJobEntity> byId(Iterable<RecommendJobEntity> jobs) {
        Map<UUID, RecommendJobEntity> out = new HashMap<>();
        jobs.forEach(j -> out.put(j.getJobId(), j));
        return out;
    }

    // ── 3) 같은 것을 여러 번 저장한 것을 묶는다 ───────────────────

    /** 같은 사람이 같은 원본에서 같은 세트를 저장한 것들. */
    private record Group(List<Session> members) {

        Session head() {
            return members.get(0);
        }

        Long userId() {
            return head().schedule().getUserId();
        }
    }

    private List<Group> dedupe(List<Session> sessions) {
        Map<String, List<Session>> buckets = new LinkedHashMap<>();
        for (Session s : sessions) {
            String key = s.schedule().getUserId() + "|" + s.originJobId() + "|" + s.savedSetKey();
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        return buckets.values().stream().map(Group::new).toList();
    }

    /** 저장된 장소 묶음을 한 문자열로. 같은 세트인지 가리는 데만 쓴다. */
    private String savedSetKey(ScheduleEntity schedule) {
        return String.join(",", savedContentIds(schedule));
    }

    // ── 4) 한 줄로 만든다 ────────────────────────────────────────

    private Map<Long, List<ScheduleArrivalEntity>> readArrivals(List<Group> groups) {
        Set<Long> ids = new HashSet<>();
        for (Group g : groups) {
            for (Session s : g.members()) {
                ids.add(s.schedule().getScheduleId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ScheduleArrivalEntity>> out = new HashMap<>();
        for (ScheduleArrivalEntity a : arrivalRepository.findByScheduleIdIn(ids)) {
            out.computeIfAbsent(a.getScheduleId(), k -> new ArrayList<>()).add(a);
        }
        return out;
    }

    private Map<Long, List<NearbyImpressionEntity>> readImpressions(List<Group> groups) {
        Set<Long> ids = new HashSet<>();
        for (Group g : groups) {
            for (Session s : g.members()) {
                ids.add(s.schedule().getScheduleId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<NearbyImpressionEntity>> out = new HashMap<>();
        for (NearbyImpressionEntity e : impressionRepository.findByScheduleIdIn(ids)) {
            out.computeIfAbsent(e.getScheduleId(), k -> new ArrayList<>()).add(e);
        }
        return out;
    }

    private TrainingExportRow toRow(Group group,
                                    Map<Long, List<ScheduleArrivalEntity>> arrivalsBySchedule,
                                    Map<Long, List<NearbyImpressionEntity>> impressionsBySchedule,
                                    String exportedAt, String salt) {
        Session head = group.head();
        ScheduleEntity schedule = head.schedule();
        JsonNode payload = head.trainingPayload();

        List<String> saved = savedContentIds(schedule);
        Set<String> savedSet = new LinkedHashSet<>(saved);
        List<String> chosen = textList(payload.path("chosen_content_ids"));
        Set<String> chosenSet = new LinkedHashSet<>(chosen);

        Set<String> arrived = new LinkedHashSet<>();
        for (Session s : group.members()) {
            for (ScheduleArrivalEntity a : arrivalsBySchedule.getOrDefault(
                    s.schedule().getScheduleId(), List.of())) {
                String cid = contentIdAt(s.schedule(), a.getDay(), a.getStopOrder());
                if (cid != null) {
                    arrived.add(cid);
                }
            }
        }

        JsonNode scores = payload.path("scores_pre_cap");
        List<TrainingExportRow.Candidate> candidates = new ArrayList<>();
        for (JsonNode c : payload.path("candidates")) {
            String cid = text(c, "content_id");
            candidates.add(new TrainingExportRow.Candidate(
                    cid,
                    c.hasNonNull("rank") ? c.get("rank").asInt() : null,
                    text(c, "name"),
                    text(c, "category"),
                    text(c, "category_group_code"),
                    c.hasNonNull("lat") ? c.get("lat").asDouble() : null,
                    c.hasNonNull("lng") ? c.get("lng").asDouble() : null,
                    cid != null && scores.hasNonNull(cid) ? scores.get(cid).asDouble() : null,
                    cid != null && chosenSet.contains(cid),
                    cid != null && savedSet.contains(cid),
                    cid != null && arrived.contains(cid)));
        }

        // 여러 벌 중 하나라도 살아 있으면 거절이 아니다. 전부 지웠을 때만 부정 신호로 본다.
        boolean allDeleted = group.members().stream()
                .allMatch(s -> s.schedule().getDeletedAt() != null);
        boolean anyDeleted = group.members().stream()
                .anyMatch(s -> s.schedule().getDeletedAt() != null);

        // 묶인 사본 전체의 주변 기록을 합친다. 같은 세트를 저장한 것들이라
        // 어느 벌에서 봤든 그 세션에서 본 것이다.
        List<TrainingExportRow.NearbyImpression> nearby = new ArrayList<>();
        for (Session s : group.members()) {
            for (NearbyImpressionEntity e : impressionsBySchedule.getOrDefault(
                    s.schedule().getScheduleId(), List.of())) {
                nearby.add(new TrainingExportRow.NearbyImpression(
                        e.getDay(), e.getStopOrder(), e.getCategory(),
                        e.getContentId(), e.getRank(), e.getClickedAt() != null));
            }
        }
        int nearbyClicked = (int) nearby.stream()
                .filter(TrainingExportRow.NearbyImpression::clicked).count();

        String exclusion = l1ExclusionReason(head, candidates, savedSet);
        SplitKey split = splitKey(payload, head.originJobId());

        return new TrainingExportRow(
                EXPORT_SCHEMA_VERSION,
                exportedAt,
                schedule.getScheduleId(),
                schedule.getUserId() == null ? null : userRef(schedule.getUserId(), salt),
                schedule.getUserId() != null,
                String.valueOf(schedule.getJobId()),
                String.valueOf(head.originJobId()),
                head.originResolution(),
                orUnknown(head.savedJob().getMode()),
                orUnknown(head.savedJob().getSource()),
                head.originJob() == null ? UNKNOWN : orUnknown(head.originJob().getMode()),
                head.originJob() == null ? UNKNOWN : orUnknown(head.originJob().getSource()),
                head.trainingSchemaVersion(),
                split.key(),
                split.source(),
                nullIfMissing(payload.path("request")),
                nullIfMissing(payload.path("ranking_config")),
                head.savedJob().getUserSegment(),
                new TrainingExportRow.Schedule(
                        String.valueOf(schedule.getDateStart()),
                        String.valueOf(schedule.getDateEnd()),
                        schedule.getTransport(),
                        schedule.getActiveStartHour(),
                        schedule.getActiveEndHour(),
                        earliest(group, s -> s.schedule().getCreatedAt()),
                        earliest(group, s -> s.schedule().getStartedAt()),
                        group.members().size(),
                        anyDeleted),
                candidates,
                new TrainingExportRow.Labels(
                        saved, chosen,
                        rejectedContentIds(head), rejectedSource(head),
                        List.copyOf(arrived), allDeleted),
                nearby,
                new TrainingExportRow.Counts(
                        candidates.size(), savedSet.size(), chosenSet.size(), arrived.size(),
                        nearby.size(), nearbyClicked),
                exclusion == null,
                exclusion);
    }

    // ── 조각들 ───────────────────────────────────────────────────

    /**
     * 저장된 일정에 실린 장소들. 이것이 정답이다.
     *
     * <p>실측 근거가 없어 만들어 낸 장소는 식별자가 없다. 후보와 이을 수 없으므로
     * 여기서 빠지고, 그런 세션은 아래에서 학습 대상에서 제외된다.
     */
    private List<String> savedContentIds(ScheduleEntity schedule) {
        List<String> out = new ArrayList<>();
        JsonNode payload = schedule.getPayload();
        if (payload == null) {
            return out;
        }
        for (JsonNode p : payload.path("places")) {
            String cid = text(p, "content_id");
            if (cid != null && !out.contains(cid)) {
                out.add(cid);
            }
        }
        return out;
    }

    /**
     * 도착 기록이 가리키는 자리의 장소 식별자.
     *
     * <p>순번은 방문 순서대로 1 부터 매겨진다. 그러니 방문 순서를 펼친 뒤 그
     * 자리를 집으면 된다. 일차가 어긋나면 옛 일정에 새 기록이 붙은 것이므로 버린다.
     */
    private String contentIdAt(ScheduleEntity schedule, Integer day, Integer stopOrder) {
        if (day == null || stopOrder == null || schedule.getPayload() == null) {
            return null;
        }
        JsonNode payload = schedule.getPayload();
        JsonNode order = payload.path("visit_order");
        JsonNode places = payload.path("places");
        if (!order.isArray() || !places.isArray() || stopOrder < 1 || stopOrder > order.size()) {
            return null;
        }
        int placeId = order.get(stopOrder - 1).asInt(-1);
        for (JsonNode p : places) {
            if (p.path("place_id").asInt(-1) == placeId) {
                if (p.path("day").asInt(1) != day) {
                    return null;
                }
                return text(p, "content_id");
            }
        }
        return null;
    }

    /** 다시 짜기로 통째로 물린 앞의 결과. 그 밖에는 빈 목록. */
    private List<String> rejectedContentIds(Session session) {
        if (!"research".equals(session.savedJob().getMode())
                || session.savedJob().getParentJobId() == null) {
            return List.of();
        }
        return jobRepository.findById(session.savedJob().getParentJobId())
                .map(parent -> {
                    List<String> out = new ArrayList<>();
                    JsonNode result = parent.getResultPayload();
                    if (result != null) {
                        for (JsonNode p : result.path("places")) {
                            String cid = text(p, "content_id");
                            if (cid != null) {
                                out.add(cid);
                            }
                        }
                    }
                    return out;
                })
                .orElse(List.of());
    }

    private String rejectedSource(Session session) {
        return rejectedContentIds(session).isEmpty() ? "unavailable" : "parent_result_payload";
    }

    /** 후보 랭킹 학습에 못 쓰는 세션인지, 쓴다면 왜 못 쓰는지. */
    private String l1ExclusionReason(Session session,
                                     List<TrainingExportRow.Candidate> candidates,
                                     Set<String> saved) {
        if ("route".equals(session.savedJob().getMode())
                || (session.originJob() != null && "route".equals(session.originJob().getMode()))) {
            // 사용자가 장소를 직접 골라 동선만 다시 만든 것이라 후보가 없다.
            return "route_session";
        }
        if (candidates.isEmpty()) {
            return "no_candidates";
        }
        if (saved.isEmpty()) {
            // 실측 근거 없이 만들어 낸 장소만 저장된 세션. 이을 정답이 없다.
            return "no_saved_content_id";
        }
        return null;
    }

    private record SplitKey(String key, String source) {
    }

    /**
     * 배우는 쪽과 재는 쪽을 가르는 기준.
     *
     * <p>요청 조건이 남아 있으면 그것으로 만든다 — 같은 조건이면 잡이 달라도 한
     * 바구니에 들어가야 한다. 조건이 없는 옛 신호는 잡 단위로 떨어뜨리되, 그렇게
     * 떨어진 것임을 함께 적어 읽는 쪽이 알 수 있게 한다.
     */
    private SplitKey splitKey(JsonNode payload, UUID originJobId) {
        JsonNode request = payload.path("request");
        if (request.isObject()) {
            try {
                return new SplitKey(
                        cacheKeyBuilder.hash(toRecommendRequest(request)), "request");
            } catch (RuntimeException e) {
                // 조건이 있는데도 못 만든 것이라 옛 신호와는 다르다. 세는 쪽이
                // 요약에서 잡으므로 여기서는 자세한 사유만 남긴다.
                log.debug("split key from request failed, falling back: {}", e.toString());
            }
        }
        return new SplitKey(sha256Hex("job:" + originJobId), "origin_job_fallback");
    }

    /**
     * 신호에 실린 요청 뷰를 캐시 열쇠가 아는 모양으로 되돌린다.
     *
     * <p>예산 칸 이름 하나만 다르다(신호는 단위를 붙여 적는다). 나머지는 그대로다.
     */
    private map.service.user.recommend.dto.RecommendRequest toRecommendRequest(JsonNode view) {
        ObjectNode copy = objectMapper.createObjectNode();
        copy.set("date", view.get("date"));
        copy.set("theme", view.get("theme"));
        copy.put("province", view.path("province").asText(null));
        copy.put("city", view.path("city").asText(null));
        if (view.hasNonNull("mobility")) {
            copy.put("mobility", view.get("mobility").asText());
        }
        if (view.hasNonNull("budget_krw")) {
            copy.put("budget", view.get("budget_krw").asInt());
        }
        return objectMapper.convertValue(
                copy, map.service.user.recommend.dto.RecommendRequest.class);
    }

    /**
     * 원 식별자 대신 쓸 지문.
     *
     * <p>소금을 섞는 이유: 식별자만 해시하면 값의 폭이 좁아 되돌리기 쉽다. 소금은
     * 파일 밖에 두고, 같은 소금으로 뽑은 파일끼리만 사람이 이어진다.
     */
    private String userRef(Long userId, String salt) {
        return "u_" + sha256Hex(salt + ":" + userId).substring(0, 16);
    }

    private boolean isTestAccount(Long userId, Collection<String> domains) {
        return userRepository.findById(userId)
                .map(u -> {
                    String email = u.getEmail();
                    if (email == null) {
                        return false;
                    }
                    String lower = email.toLowerCase(Locale.ROOT);
                    return domains.stream()
                            .map(d -> "@" + d.trim().toLowerCase(Locale.ROOT))
                            .anyMatch(lower::endsWith);
                })
                // 계정을 못 찾으면 시험용인지 알 수 없다. 지운 계정일 수 있어
                // 진짜 사용자로 세지 않는다.
                .orElse(true);
    }

    private String earliest(Group group,
                            java.util.function.Function<Session, OffsetDateTime> pick) {
        return group.members().stream()
                .map(pick)
                .filter(java.util.Objects::nonNull)
                .min(OffsetDateTime::compareTo)
                .map(String::valueOf)
                .orElse(null);
    }

    private static List<String> textList(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            if (n.isTextual()) {
                out.add(n.asText());
            }
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static JsonNode nullIfMissing(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node;
    }

    private static String orUnknown(String value) {
        return value == null ? UNKNOWN : value;
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

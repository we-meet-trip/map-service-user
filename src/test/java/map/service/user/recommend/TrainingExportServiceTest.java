package map.service.user.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import map.service.user.domain.user.entity.AuthProvider;
import map.service.user.domain.user.entity.User;
import map.service.user.domain.user.repository.UserRepository;
import map.service.user.nearby.NearbyImpressionEntity;
import map.service.user.nearby.NearbyImpressionRepository;
import map.service.user.recommend.dto.TrainingExportRow;
import map.service.user.schedule.ScheduleArrivalEntity;
import map.service.user.schedule.ScheduleArrivalRepository;
import map.service.user.schedule.ScheduleEntity;
import map.service.user.schedule.ScheduleExportRepository;
import map.service.user.schedule.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * 흩어진 조각을 학습에 쓸 수 있는 한 줄로 잇는 규칙 (H2).
 *
 * <p>여기서 지키는 것은 크게 넷이다.
 * <ul>
 *   <li>후보를 실제로 가진 잡을 찾아간다 — 캐시로 답한 세션은 저장한 잡에 후보가 없다.
 *   <li>다시 짜기의 계보는 따라가지 않는다 — 그쪽은 "복사해 온 곳" 이 아니라
 *       "물려서 버린 것" 이라, 따라가면 사용자가 거절한 후보로 학습하게 된다.
 *   <li>같은 것을 여러 번 저장했으면 증거를 합친다 — 한 벌만 고르면 "지웠다" 나
 *       "실제로 갔다" 중 하나가 통째로 사라진다.
 *   <li>정답은 저장된 일정이지 모델이 고른 것이 아니다.
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@DisplayName("학습 자료 조립 (H2)")
class TrainingExportServiceTest {

    private static final String SALT = "소금";
    private static final List<String> TEST_DOMAINS = List.of("admin.map", "test.com");

    @Autowired
    private ScheduleExportRepository scheduleExportRepository;
    @Autowired
    private ScheduleRepository scheduleRepository;
    @Autowired
    private ScheduleArrivalRepository arrivalRepository;
    @Autowired
    private NearbyImpressionRepository impressionRepository;
    @Autowired
    private RecommendJobRepository jobRepository;
    @Autowired
    private RecommendTrainingRepository trainingRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TestEntityManager entityManager;

    // 실제로 주입되는 것과 같은 모양이어야 한다. 날짜를 다룰 줄 모르는 것을 쓰면
    // 요청 조건을 되돌리지 못해 나누기 기준이 조용히 잡 단위로 떨어진다.
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule()).build();
    private TrainingExportService service;

    @BeforeEach
    void setUp() {
        service = new TrainingExportService(
                scheduleExportRepository, arrivalRepository, impressionRepository,
                jobRepository,
                trainingRepository, userRepository,
                new RecommendCacheKey(50000, 60), objectMapper, 500);
    }

    // ── 픽스처 ────────────────────────────────────────────────────

    private Long user(String email) {
        User u = userRepository.saveAndFlush(
                User.builder().email(email).nickname("nick")
                        .authProvider(AuthProvider.EMAIL).build());
        return u.getId();
    }

    private UUID job(String mode, String source, UUID parent, ObjectNode segment) {
        UUID id = UUID.randomUUID();
        RecommendJobEntity e = new RecommendJobEntity(id, null, "done", null, null, null);
        e.fillUserSegmentIfAbsent(segment);
        jobRepository.saveAndFlush(e);
        // mode/source/parent 는 네이티브 갱신으로만 채워지므로 여기서 직접 넣는다.
        entityManager.getEntityManager().createNativeQuery(
                        "UPDATE user_service.recommend_jobs SET mode=?1, source=?2,"
                                + " parent_job_id=?3 WHERE job_id=?4")
                .setParameter(1, mode).setParameter(2, source)
                .setParameter(3, parent).setParameter(4, id)
                .executeUpdate();
        return id;
    }

    /** 후보 3개, 그중 2개를 모델이 고른 신호. */
    private void signal(UUID jobId, boolean withRequest) {
        ObjectNode p = objectMapper.createObjectNode();
        p.put("schema_version", 2).put("path", "select");
        if (withRequest) {
            ObjectNode req = p.putObject("request");
            ObjectNode date = req.putObject("date");
            date.put("date_start", "2026-09-05").put("date_end", "2026-09-05")
                    .put("time_start", "10:00:00").put("time_end", "18:00:00");
            req.put("budget_krw", 100000);
            req.putArray("theme").add("산책");
            req.put("mobility", "walk").put("province", "서울특별시").put("city", "종로구");
            p.putObject("ranking_config")
                    .put("personalization_enabled", true).put("rules_enabled", true);
        }
        var cands = p.putArray("candidates");
        for (int i = 0; i < 3; i++) {
            cands.addObject().put("rank", i).put("content_id", "kakao:" + i)
                    .put("name", "후보" + i).put("category", "여행")
                    .put("category_group_code", "AT4")
                    .put("lat", 37.5 + i * 0.01).put("lng", 127.0);
        }
        p.putObject("scores_pre_cap").put("kakao:0", 1.0).put("kakao:1", 0.9);
        p.putArray("chosen_content_ids").add("kakao:0").add("kakao:1");
        trainingRepository.saveAndFlush(new RecommendTrainingEntity(jobId, 2, p));
    }

    /** places 0·1 을 저장한 일정. visit_order 는 방문 순서다. */
    private ScheduleEntity schedule(Long userId, UUID jobId, String... contentIds) {
        ObjectNode payload = objectMapper.createObjectNode();
        var places = payload.putArray("places");
        var order = payload.putArray("visit_order");
        for (int i = 0; i < contentIds.length; i++) {
            ObjectNode place = places.addObject().put("place_id", i).put("day", 1);
            if (contentIds[i] != null) {
                place.put("content_id", contentIds[i]);
            }
            order.add(i);
        }
        ScheduleEntity e = new ScheduleEntity(userId, jobId, "여행",
                LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 5),
                payload, "walk", 10, 18);
        scheduleRepository.saveAndFlush(e);
        return e;
    }

    private List<TrainingExportRow> run(boolean excludeTest) {
        entityManager.flush();
        entityManager.clear();
        return service.export(excludeTest, TEST_DOMAINS, SALT).rows();
    }

    // ── 원본 찾아가기 ─────────────────────────────────────────────

    @Test
    @DisplayName("캐시로 답한 세션은 후보를 가진 원본까지 따라간다")
    void cacheHitSessionFollowsLineageToTheJobThatHasCandidates() {
        UUID origin = job("refresh", "agent", null, null);
        signal(origin, true);
        UUID copy = job("init", "cache_hit", origin, null);
        Long uid = user("real@gmail.com");
        schedule(uid, copy, "kakao:0", "kakao:1");

        List<TrainingExportRow> rows = run(true);

        assertThat(rows).hasSize(1);
        TrainingExportRow r = rows.get(0);
        assertThat(r.originJobId()).isEqualTo(origin.toString());
        assertThat(r.originResolution()).isEqualTo("parent");
        // 사용자가 한 행동과 후보가 나온 자리는 다를 수 있다. 둘 다 남아야 한다.
        assertThat(r.mode()).isEqualTo("init");
        assertThat(r.originMode()).isEqualTo("refresh");
        assertThat(r.counts().candidates()).isEqualTo(3);
    }

    @Test
    @DisplayName("다시 짜기는 계보를 따라가지 않는다")
    void researchDoesNotFollowItsParent() {
        // 그쪽 계보는 "물려서 버린 앞의 결과" 다. 따라가면 사용자가 거절한 후보로
        // 학습하게 된다.
        UUID rejected = job("init", "agent", null, null);
        signal(rejected, true);
        UUID research = job("research", "agent", rejected, null);
        signal(research, true);
        Long uid = user("real@gmail.com");
        schedule(uid, research, "kakao:0");

        List<TrainingExportRow> rows = run(true);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).originJobId()).isEqualTo(research.toString());
        assertThat(rows.get(0).originResolution()).isEqualTo("self");
    }

    @Test
    @DisplayName("후보를 끝내 못 찾으면 내보내지 않는다")
    void sessionWithoutCandidatesIsDropped() {
        UUID orphan = job("init", "cache_hit", null, null);
        schedule(user("real@gmail.com"), orphan, "kakao:0");

        assertThat(run(true)).isEmpty();
    }

    // ── 묶기 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 것을 여러 번 저장하면 한 줄로 묶고 증거를 합친다")
    void duplicateSavesMergeAndKeepBothKindsOfEvidence() {
        UUID origin = job("refresh", "agent", null, null);
        signal(origin, true);
        UUID copy = job("init", "cache_hit", origin, null);
        Long uid = user("real@gmail.com");
        ScheduleEntity alive = schedule(uid, copy, "kakao:0", "kakao:1");
        ScheduleEntity removed = schedule(uid, copy, "kakao:0", "kakao:1");
        removed.markDeleted(OffsetDateTime.now());
        scheduleRepository.saveAndFlush(removed);
        // 지운 쪽에만 도착 기록이 있다 — 한 벌만 고르면 이것이 사라진다.
        arrivalRepository.saveAndFlush(new ScheduleArrivalEntity(
                removed.getScheduleId(), 1, 1, OffsetDateTime.now(), "ok"));

        List<TrainingExportRow> rows = run(true);

        assertThat(rows).hasSize(1);
        TrainingExportRow r = rows.get(0);
        assertThat(r.schedule().duplicateCount()).isEqualTo(2);
        assertThat(r.schedule().anyDeleted()).isTrue();
        // 한 벌이 살아 있으면 거절이 아니다. 중복 정리일 뿐이다.
        assertThat(r.labels().deleted()).isFalse();
        assertThat(r.labels().arrivedContentIds()).containsExactly("kakao:0");
        assertThat(alive.getScheduleId()).isNotNull();
    }

    @Test
    @DisplayName("여러 벌을 전부 지웠으면 그때만 물린 것으로 본다")
    void allCopiesDeletedMeansRejected() {
        UUID origin = job("refresh", "agent", null, null);
        signal(origin, true);
        UUID copy = job("init", "cache_hit", origin, null);
        Long uid = user("real@gmail.com");
        for (int i = 0; i < 2; i++) {
            ScheduleEntity e = schedule(uid, copy, "kakao:0");
            e.markDeleted(OffsetDateTime.now());
            scheduleRepository.saveAndFlush(e);
        }

        assertThat(run(true).get(0).labels().deleted()).isTrue();
    }

    // ── 라벨 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("정답은 저장한 것이지 모델이 고른 것이 아니다")
    void savedIsTheLabelNotChosen() {
        UUID origin = job("refresh", "agent", null, null);
        signal(origin, true);   // 모델은 0, 1 을 골랐다
        UUID copy = job("init", "cache_hit", origin, null);
        // 사용자는 1, 2 를 저장했다 — 모델이 고른 것과 다르다.
        schedule(user("real@gmail.com"), copy, "kakao:1", "kakao:2");

        TrainingExportRow r = run(true).get(0);

        assertThat(r.labels().savedContentIds()).containsExactly("kakao:1", "kakao:2");
        assertThat(r.labels().chosenContentIds()).containsExactly("kakao:0", "kakao:1");
        assertThat(r.candidates()).filteredOn(TrainingExportRow.Candidate::saved)
                .extracting(TrainingExportRow.Candidate::contentId)
                .containsExactly("kakao:1", "kakao:2");
        assertThat(r.candidates()).filteredOn(TrainingExportRow.Candidate::chosen)
                .extracting(TrainingExportRow.Candidate::contentId)
                .containsExactly("kakao:0", "kakao:1");
    }

    @Test
    @DisplayName("도착은 방문 순서를 펼쳐 그 자리의 장소로 잇는다")
    void arrivalMapsThroughVisitOrder() {
        UUID origin = job("refresh", "agent", null, null);
        signal(origin, true);
        UUID copy = job("init", "cache_hit", origin, null);
        ScheduleEntity s = schedule(user("real@gmail.com"), copy, "kakao:0", "kakao:1", "kakao:2");
        // 두 번째로 들른 자리 = visit_order[1] = place_id 1 = kakao:1
        arrivalRepository.saveAndFlush(new ScheduleArrivalEntity(
                s.getScheduleId(), 1, 2, OffsetDateTime.now(), "ok"));

        TrainingExportRow r = run(true).get(0);

        assertThat(r.labels().arrivedContentIds()).containsExactly("kakao:1");
        assertThat(r.counts().arrived()).isEqualTo(1);
    }

    @Test
    @DisplayName("일차가 어긋난 도착 기록은 버린다")
    void arrivalWithMismatchedDayIsIgnored() {
        // 옛 일정에 새 기록이 붙은 것이다. 잘못 이으면 안 간 곳을 갔다고 배운다.
        UUID origin = job("refresh", "agent", null, null);
        signal(origin, true);
        UUID copy = job("init", "cache_hit", origin, null);
        ScheduleEntity s = schedule(user("real@gmail.com"), copy, "kakao:0");
        arrivalRepository.saveAndFlush(new ScheduleArrivalEntity(
                s.getScheduleId(), 9, 1, OffsetDateTime.now(), "ok"));

        assertThat(run(true).get(0).labels().arrivedContentIds()).isEmpty();
    }

    // ── 나누기 기준 ───────────────────────────────────────────────

    @Test
    @DisplayName("같은 조건이면 잡이 달라도 같은 바구니에 들어간다")
    void sameRequestSharesSplitKeyEvenAcrossDifferentOriginJobs() {
        // 뒤에서 캐시를 다시 채우며 새 잡이 계속 생긴다. 잡 단위로 가르면
        // 사실상 같은 세션이 배우는 쪽과 재는 쪽으로 갈린다.
        Long uid = user("real@gmail.com");
        for (int i = 0; i < 2; i++) {
            UUID origin = job("refresh", "agent", null, null);
            signal(origin, true);
            UUID copy = job("init", "cache_hit", origin, null);
            schedule(uid, copy, "kakao:" + i);
        }

        List<TrainingExportRow> rows = run(true);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(TrainingExportRow::splitKey).hasSize(2)
                .containsOnly(rows.get(0).splitKey());
        assertThat(rows).extracting(TrainingExportRow::originJobId).doesNotHaveDuplicates();
        assertThat(rows.get(0).splitKeySource()).isEqualTo("request");
    }

    @Test
    @DisplayName("요청 조건이 없는 옛 신호는 잡 단위로 떨어지고 그 사실이 적힌다")
    void oldSignalWithoutRequestFallsBackToJobAndSaysSo() {
        UUID origin = job("refresh", "agent", null, null);
        signal(origin, false);
        UUID copy = job("init", "cache_hit", origin, null);
        schedule(user("real@gmail.com"), copy, "kakao:0");

        TrainingExportRow r = run(true).get(0);

        assertThat(r.splitKeySource()).isEqualTo("origin_job_fallback");
        assertThat(r.request()).isNull();
        assertThat(r.rankingConfig()).isNull();
    }

    // ── 거르기와 가리기 ───────────────────────────────────────────

    @Test
    @DisplayName("시험용 계정은 기본으로 빠지고, 끄면 함께 나온다")
    void testAccountsAreExcludedByDefault() {
        UUID origin = job("refresh", "agent", null, null);
        signal(origin, true);
        UUID copy = job("init", "cache_hit", origin, null);
        schedule(user("maptester1@admin.map"), copy, "kakao:0");
        schedule(user("real@gmail.com"), copy, "kakao:1");

        assertThat(run(true)).hasSize(1);
        assertThat(run(false)).hasSize(2);
    }

    @Test
    @DisplayName("사용자를 모르는 세션은 거를 때 함께 빠진다")
    void unknownUserIsExcludedWhenFiltering() {
        // 우리 점검 트래픽이 토큰 없이 도는 모양이다. 모르는 것을 진짜 사용자로
        // 세면 거르는 쪽이 새는 방향으로 틀린다.
        UUID origin = job("refresh", "agent", null, null);
        signal(origin, true);
        UUID copy = job("init", "cache_hit", origin, null);
        schedule(null, copy, "kakao:0");

        assertThat(run(true)).isEmpty();
        List<TrainingExportRow> included = run(false);
        assertThat(included).hasSize(1);
        assertThat(included.get(0).userRef()).isNull();
        assertThat(included.get(0).userKnown()).isFalse();
    }

    @Test
    @DisplayName("사람 식별자는 지문으로 바뀌어 나간다")
    void userIdBecomesAFingerprint() {
        UUID origin = job("refresh", "agent", null, null);
        signal(origin, true);
        UUID copy = job("init", "cache_hit", origin, null);
        Long uid = user("real@gmail.com");
        schedule(uid, copy, "kakao:0");

        TrainingExportRow r = run(true).get(0);

        // 지문이 곧 그 사람의 식별자를 소금과 함께 흘린 값이어야 한다.
        // (부분 문자열로 비교하지 않는다 — 16진수라 숫자 몇 자는 우연히 겹친다.)
        assertThat(r.userRef()).isEqualTo(
                "u_" + TrainingExportService.sha256Hex(SALT + ":" + uid).substring(0, 16));
        assertThat(r.userRef()).isNotEqualTo(String.valueOf(uid));
        // 같은 소금이면 두 번 뽑아도 같은 값이라 파일끼리 이어진다.
        assertThat(service.export(true, TEST_DOMAINS, SALT).rows().get(0).userRef())
                .isEqualTo(r.userRef());
        // 소금이 다르면 이어지지 않는다.
        assertThat(service.export(true, TEST_DOMAINS, "다른소금").rows().get(0).userRef())
                .isNotEqualTo(r.userRef());
    }

    // ── 학습에 쓸 수 있는 세션인지 ────────────────────────────────

    @Test
    @DisplayName("이을 정답이 없는 세션은 학습 대상에서 뺀다")
    void sessionWithoutAnyIdentifiableSavedPlaceIsExcluded() {
        // 만들어 낸 장소만 저장된 경우다. 식별자가 없어 후보와 이을 수 없다.
        UUID origin = job("refresh", "agent", null, null);
        signal(origin, true);
        UUID copy = job("init", "cache_hit", origin, null);
        schedule(user("real@gmail.com"), copy, new String[]{null});

        TrainingExportRow r = run(true).get(0);

        assertThat(r.l1Eligible()).isFalse();
        assertThat(r.l1ExclusionReason()).isEqualTo("no_saved_content_id");
    }

    @Test
    @DisplayName("직접 고른 동선 세션도 학습 대상에서 뺀다")
    void routeSessionIsExcluded() {
        // 사용자가 장소를 직접 골랐으므로 후보 중에서 고른 것이 아니다.
        UUID origin = job("route", "agent", null, null);
        signal(origin, true);
        schedule(user("real@gmail.com"), origin, "kakao:0");

        TrainingExportRow r = run(true).get(0);

        assertThat(r.l1Eligible()).isFalse();
        assertThat(r.l1ExclusionReason()).isEqualTo("route_session");
    }

    @Test
    @DisplayName("주변에서 보여 준 것과 눌린 것이 함께 실린다")
    void nearbyImpressionsRideAlong() {
        // 눌린 것만 실으면 "안 눌렀다" 가 "안 보였다" 인지 "보고 안 골랐다" 인지
        // 구분되지 않아 반례로 쓸 수 없다.
        UUID origin = job("refresh", "agent", null, null);
        signal(origin, true);
        UUID copy = job("init", "cache_hit", origin, null);
        ScheduleEntity sch = schedule(user("real@gmail.com"), copy, "kakao:0");

        NearbyImpressionEntity shown = new NearbyImpressionEntity(
                sch.getScheduleId(), 1, 1, "cafe", "kakao:cafe-1", 0, OffsetDateTime.now());
        NearbyImpressionEntity clicked = new NearbyImpressionEntity(
                sch.getScheduleId(), 1, 1, "cafe", "kakao:cafe-2", 1, OffsetDateTime.now());
        clicked.markClicked(OffsetDateTime.now());
        impressionRepository.saveAndFlush(shown);
        impressionRepository.saveAndFlush(clicked);

        TrainingExportRow r = run(true).get(0);

        assertThat(r.nearby()).hasSize(2);
        assertThat(r.counts().nearbyShown()).isEqualTo(2);
        assertThat(r.counts().nearbyClicked()).isEqualTo(1);
        assertThat(r.nearby()).filteredOn(TrainingExportRow.NearbyImpression::clicked)
                .extracting(TrainingExportRow.NearbyImpression::contentId)
                .containsExactly("kakao:cafe-2");
        // 몇 번째로 보였는지가 남아야 노출 편향을 보정할 수 있다.
        assertThat(r.nearby()).extracting(TrainingExportRow.NearbyImpression::rank)
                .containsExactlyInAnyOrder(0, 1);
    }

    @Test
    @DisplayName("지운 일정도 자료에 들어온다")
    void tombstonedSessionIsStillExported() {
        // 저장했다가 물린 것이 부정 신호다. 빠지면 그 판단이 사라진다.
        UUID origin = job("refresh", "agent", null, null);
        signal(origin, true);
        UUID copy = job("init", "cache_hit", origin, null);
        ScheduleEntity e = schedule(user("real@gmail.com"), copy, "kakao:0");
        e.markDeleted(OffsetDateTime.now());
        scheduleRepository.saveAndFlush(e);

        List<TrainingExportRow> rows = run(true);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).labels().deleted()).isTrue();
    }
}

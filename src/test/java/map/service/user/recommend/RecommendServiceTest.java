package map.service.user.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import map.service.user.recommend.dto.EditRequest;
import map.service.user.recommend.dto.Place;
import java.util.Optional;
import java.util.concurrent.Executor;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.recommend.dto.DateRange;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.Mobility;
import map.service.user.recommend.dto.RecommendRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * RecommendServiceTest — B2 확장 계약(stage/exclude)과 재사용 캐시 분기 검증
 *
 * create: 클라이언트 stage/exclude 무시하고 init/[] 강제. 캐시 미스면 agent 호출 +
 *         job_id→hash 연결 + PG in_progress 기록, 히트면 캐시 draft 를 새 job_id 로
 *         즉시 복사하고 PG 에 완료로 기록한다(캐시 히트는 완료 이벤트가 오지 않으므로).
 * research: draft places[].content_id 수집(비-텍스트/공백/중복 제외) 후
 *           mode1 + exclude 로 재구성, draft 폐기 순서 보장. 캐시는 타지 않는다.
 *
 * 캐시 테스트가 쓰는 cacheRequest 는 stage="init"/exclude=[] 로 만들어 두어
 * 서비스의 서버측 정규화(withStage) 결과와 레코드 동등이 되게 한다 — 덕분에
 * agentClient 스텁을 정규화 전/후 구분 없이 그대로 매칭할 수 있다.
 */
class RecommendServiceTest {

    private AgentClient agentClient;
    private DraftStore draftStore;
    private ResearchLimitService researchLimitService;
    private RecommendJobStore jobStore;
    private ReuseCacheStore reuseCacheStore;
    private ProfileThemeProvider profileThemeProvider;
    private RecommendService service;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecommendCacheKey cacheKeyBuilder = new RecommendCacheKey(50_000, 60);
    private final Executor immediateExecutor = Runnable::run;

    /** 재사용 캐시 시나리오 전용 요청(이미 정규화된 형태). */
    private RecommendRequest cacheRequest;

    /** 저장된 payload JSON 에서 job_id 를 읽는다. 없으면 null. */
    private String readJobId(String payloadJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    objectMapper.readTree(payloadJson);
            return node.has("job_id") ? node.get("job_id").asText() : null;
        } catch (Exception e) {
            throw new AssertionError("payload 가 유효한 JSON 이 아니다: " + payloadJson, e);
        }
    }

    @BeforeEach
    void setUp() {
        agentClient = mock(AgentClient.class);
        draftStore = mock(DraftStore.class);
        researchLimitService = mock(ResearchLimitService.class);
        jobStore = mock(RecommendJobStore.class);
        reuseCacheStore = mock(ReuseCacheStore.class);
        when(reuseCacheStore.waitingTtl()).thenReturn(java.time.Duration.ofSeconds(600));
        when(jobStore.finishWaiting(anyString(), nullable(String.class), anyString(), anyString(), anyString()))
                .thenAnswer(call -> Optional.of(call.getArgument(3)));
        profileThemeProvider = mock(ProfileThemeProvider.class);
        // 기본은 "저장된 취향 없음". 이 상태에서 기존 테스트들이 전부
        // 도입 전과 같은 경로를 타야 한다 — 그게 회귀 방어선이다.
        when(profileThemeProvider.themesFor(any())).thenReturn(List.of());
        service = new RecommendService(
                agentClient, draftStore, objectMapper, researchLimitService, jobStore,
                reuseCacheStore, cacheKeyBuilder, profileThemeProvider,
                immediateExecutor, 3L, mock(map.service.user.schedule.ScheduleRepository.class), mock(map.service.user.policy.AiConsentService.class));
        when(agentClient.requestRecommend(any()))
                .thenReturn(new JobAccepted("job-2", "in_progress", 3));
        // 기본은 "이 요청이 만드는 쪽". 같은 조건이 겹치는 상황은 개별
        // 테스트에서 false 로 바꿔 확인한다.
        when(reuseCacheStore.claimProducer(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        // 기본은 한도 이내(허용). 한도 초과 시나리오는 개별 테스트에서 재정의한다.
        when(researchLimitService.tryConsume(anyString())).thenReturn(true);

        cacheRequest = new RecommendRequest(
                new DateRange(
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 3),
                        LocalTime.of(10, 0),
                        LocalTime.of(20, 0)),
                100_000,
                List.of("역사"),
                Mobility.WALK,
                "서울특별시",
                "동작구",
                "sched-9",
                "init",
                List.of(),
                null);
    }

    private static RecommendRequest request(String stage, List<String> exclude) {
        return new RecommendRequest(
                new DateRange(
                        LocalDate.of(2026, 7, 6),
                        LocalDate.of(2026, 7, 6),
                        LocalTime.of(9, 0),
                        LocalTime.of(18, 0)),
                null,
                List.of("산책"),
                null,
                "서울특별시",
                "강남구",
                "sched-1",
                stage,
                exclude,
                null);
    }

    // ---- B2 계약: 서버측 stage/exclude 강제 ----

    @Test
    void createForcesInitStageAndEmptyExclude() {
        // 클라이언트가 악의적으로 mode1/exclude 를 보내도 서버가 재구성한다.
        service.createRecommendation(request("mode1", List.of("cheat")));

        ArgumentCaptor<RecommendRequest> captor =
                ArgumentCaptor.forClass(RecommendRequest.class);
        verify(agentClient).requestRecommend(captor.capture());
        RecommendRequest sent = captor.getValue();
        assertThat(sent.stage()).isEqualTo("init");
        assertThat(sent.exclude()).isEmpty();
        assertThat(sent.scheduleId()).isEqualTo("sched-1");
        assertThat(sent.province()).isEqualTo("서울특별시");
    }

    @Test
    void createWritesInProgressJobRecord() {
        service.createRecommendation(request("init", List.of()));

        // 접수 직후 발급된 job_id·scheduleId 와 함께 출처와 지역을 남긴다.
        // 출처가 없으면 캐시로 답한 잡과 구분되지 않아 세는 쪽이 agent 실행
        // 횟수를 부풀려 읽고, 지역이 없으면 이 작업으로 저장될 일정이 나중에
        // 날씨를 다시 물을 곳을 잃는다.
        verify(jobStore).insertInProgress("job-2", "sched-1",
                RecommendJobStore.JobOrigin.agent("init"), "서울특별시", "강남구");
    }

    // ---- research(Mode 1) ----

    @Test
    void researchCollectsContentIdsIntoExclude() {
        String draft = """
                {"job_id":"job-1","status":"done","places":[
                  {"place_id":0,"content_id":"kakao:1"},
                  {"place_id":1,"content_id":"durunubi:7"},
                  {"place_id":2,"content_id":null},
                  {"place_id":3},
                  {"place_id":4,"content_id":"kakao:1"},
                  {"place_id":5,"content_id":"  "}
                ]}""";
        when(draftStore.find("job-1")).thenReturn(Optional.of(draft));

        service.research("job-1", request(null, null));

        ArgumentCaptor<RecommendRequest> captor =
                ArgumentCaptor.forClass(RecommendRequest.class);
        verify(agentClient).requestRecommend(captor.capture());
        RecommendRequest sent = captor.getValue();
        assertThat(sent.stage()).isEqualTo("mode1");
        // null/부재/공백/중복 content_id 는 제외, 순서 보존
        assertThat(sent.exclude())
                .containsExactly("kakao:1", "durunubi:7");
        verify(draftStore, never()).delete("job-1");
    }

    @Test
    void researchWithoutDraftSendsEmptyExclude() {
        when(draftStore.find("job-x")).thenReturn(Optional.empty());

        service.research("job-x", request(null, null));

        ArgumentCaptor<RecommendRequest> captor =
                ArgumentCaptor.forClass(RecommendRequest.class);
        verify(agentClient).requestRecommend(captor.capture());
        assertThat(captor.getValue().stage()).isEqualTo("mode1");
        assertThat(captor.getValue().exclude()).isEmpty();
    }

    @Test
    void researchWithCorruptDraftSendsEmptyExclude() {
        when(draftStore.find("job-y"))
                .thenReturn(Optional.of("not-a-json"));

        service.research("job-y", request(null, null));

        ArgumentCaptor<RecommendRequest> captor =
                ArgumentCaptor.forClass(RecommendRequest.class);
        verify(agentClient).requestRecommend(captor.capture());
        assertThat(captor.getValue().exclude()).isEmpty();
    }

    @Test
    void researchFallsBackToStoredPayloadForExclude() {
        // Redis draft 는 만료·일정 저장으로 사라진다. 그때도 PG 에 남은 완료
        // 결과에서 제외 목록을 세워야 "다른 장소"라는 약속이 지켜진다.
        when(draftStore.find("job-z")).thenReturn(Optional.empty());
        when(jobStore.findFinishedPayload("job-z")).thenReturn(Optional.of("""
                {"job_id":"job-z","status":"done","places":[
                  {"place_id":0,"content_id":"kakao:9"},
                  {"place_id":1,"content_id":"durunubi:3"}
                ]}"""));

        service.research("job-z", request(null, null));

        ArgumentCaptor<RecommendRequest> captor =
                ArgumentCaptor.forClass(RecommendRequest.class);
        verify(agentClient).requestRecommend(captor.capture());
        assertThat(captor.getValue().exclude())
                .containsExactly("kakao:9", "durunubi:3");
    }

    @Test
    void researchUsesScheduleBucketWhenScheduleIdPresent() {
        // scheduleId 가 있으면 "sched:{id}" 버킷으로 카운트한다.
        service.research("job-1", request(null, null));

        verify(researchLimitService).tryConsume("sched:sched-1");
    }

    @Test
    void researchOverLimitThrowsConflictAndSkipsSideEffects() {
        when(researchLimitService.tryConsume(anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.research("job-1", request(null, null)))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.RESEARCH_LIMIT_EXCEEDED));

        // 한도 초과 시 agent 호출도 draft 삭제도 일어나지 않는다.
        verify(agentClient, never()).requestRecommend(any());
        verify(draftStore, never()).delete(anyString());
    }

    @Test
    void researchNeverConsultsReuseCache() {
        // 재탐색은 사용자가 명시적으로 다른 결과를 원하는 액션이므로 캐시를 타지 않는다.
        when(draftStore.find("job-1")).thenReturn(Optional.empty());

        service.research("job-1", request(null, null));

        verify(reuseCacheStore, never()).find(anyString());
    }

    private static Place place(int id) {
        return new Place(id, 1, "장소" + id, "주소", 37.5, 127.0, "10:00",
                null, null, null, true, null, null, null, null, null, null);
    }

    // ---- 초안 수정: 앞뒤가 맞는지 ----

    /** 순서와 이동 구간은 장소를 자리 번호로 가리키므로, 장소만 줄이면 없는 자리를 가리키게 된다. */
    @Test
    void editRejectedWhenVisitOrderPointsPastPlaces() {
        String draft = "{\"places\":[{\"name\":\"가\"},{\"name\":\"나\"}],"
                + "\"visit_order\":[0,1],\"legs\":[]}";
        when(draftStore.find("job-1")).thenReturn(Optional.of(draft));
        when(jobStore.persistOwnedEdit(eq("job-1"), any(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    java.util.function.UnaryOperator<String> merge = invocation.getArgument(4);
                    String after = merge.apply(draft);
                    return new RecommendJobStore.EditOutcome(after, after);
                });

        // 장소를 하나로 줄이면서 순서를 함께 보내지 않았다.
        EditRequest edit = new EditRequest(
                List.of(new Place(0, 1, "가", "주소", 37.5, 127.0, "10:00",
                        null, null, null, true, null, null, null, null, null, null)), null, null);

        assertThatThrownBy(() -> service.applyEdit("job-1", edit, 7L, null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RECOMMEND_EDIT_INCONSISTENT);
        verify(draftStore, never()).save(anyString(), anyString());
    }

    /** 자리 번호가 아니라 place_id 로 가리키므로, 남은 번호가 띄엄띄엄해도 맞는 것이다. */
    @Test
    void editAcceptedWhenRemainingIdsAreSparse() {
        String draft = "{\"places\":[{\"place_id\":0},{\"place_id\":1},"
                + "{\"place_id\":2},{\"place_id\":3}],"
                + "\"visit_order\":[0,1,2,3],\"legs\":[]}";
        when(draftStore.find("job-1")).thenReturn(Optional.of(draft));
        when(jobStore.persistOwnedEdit(eq("job-1"), any(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    java.util.function.UnaryOperator<String> merge = invocation.getArgument(4);
                    String after = merge.apply(draft);
                    return new RecommendJobStore.EditOutcome(after, after);
                });

        // 가운데 둘을 빼고 0 과 3 만 남겼다. 번호가 이어지지 않지만 맞는 것이다.
        EditRequest edit = new EditRequest(
                List.of(place(0), place(3)), List.of(0, 3), List.of());

        assertThat(service.applyEdit("job-1", edit, 7L, null)).isPresent();
    }

    @Test
    void editAcceptedWhenOrderShrinksTogether() {
        String draft = "{\"places\":[{\"name\":\"가\"},{\"name\":\"나\"}],"
                + "\"visit_order\":[0,1],\"legs\":[]}";
        when(draftStore.find("job-1")).thenReturn(Optional.of(draft));
        when(jobStore.persistOwnedEdit(eq("job-1"), any(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    java.util.function.UnaryOperator<String> merge = invocation.getArgument(4);
                    String after = merge.apply(draft);
                    return new RecommendJobStore.EditOutcome(after, after);
                });

        EditRequest edit = new EditRequest(
                List.of(new Place(0, 1, "가", "주소", 37.5, 127.0, "10:00",
                        null, null, null, true, null, null, null, null, null, null)),
                List.of(0), List.of());

        assertThat(service.applyEdit("job-1", edit, 7L, null)).isPresent();
        verify(draftStore).save(eq("job-1"), anyString());
    }

    // ---- draft 조회 (Redis → PG 폴백) ----

    @Test
    void findDraftRedisHitAfterPgMiss() {
        when(draftStore.find("job-1")).thenReturn(Optional.of("{\"a\":1}"));

        Optional<String> result = service.findDraft("job-1");

        assertThat(result).contains("{\"a\":1}");
        verify(jobStore).findFinishedPayload("job-1");
    }

    @Test
    void findDraftPgHitDoesNotDependOnRedis() {
        when(draftStore.find("job-1")).thenReturn(Optional.empty());
        when(jobStore.findFinishedPayload("job-1"))
                .thenReturn(Optional.of("{\"status\":\"done\"}"));

        Optional<String> result = service.findDraft("job-1");

        assertThat(result).contains("{\"status\":\"done\"}");
        // Redis 재적재(re-warm) 확인.
        verify(draftStore, never()).find(anyString());
        verify(draftStore, never()).save(anyString(), anyString());
    }

    @Test
    void findDraftRedisMissPgMissReturnsEmpty() {
        when(draftStore.find("job-x")).thenReturn(Optional.empty());
        when(jobStore.findFinishedPayload("job-x")).thenReturn(Optional.empty());

        Optional<String> result = service.findDraft("job-x");

        assertThat(result).isEmpty();
        verify(draftStore, never()).save(anyString(), anyString());
    }

    // ---- 재사용 캐시 분기 ----

    @Test
    void missCallsAgentAndLinksJob() {
        String hash = cacheKeyBuilder.hash(cacheRequest);
        when(reuseCacheStore.find(hash)).thenReturn(Optional.empty());
        JobAccepted agentResult = new JobAccepted("agent-job-1", "in_progress", 3);
        when(agentClient.requestRecommend(cacheRequest)).thenReturn(agentResult);

        JobAccepted result = service.createRecommendation(cacheRequest);

        assertThat(result).isEqualTo(agentResult);
        verify(reuseCacheStore).linkJob("agent-job-1", hash);
        verify(draftStore, never()).save(anyString(), anyString());
    }

    @Test
    void hitBelowThresholdReturnsImmediatelyWithoutAgentCall() {
        String hash = cacheKeyBuilder.hash(cacheRequest);
        when(reuseCacheStore.find(hash)).thenReturn(Optional.of("{\"places\":[]}"));
        when(reuseCacheStore.incrementHits(hash)).thenReturn(1L);

        JobAccepted result = service.createRecommendation(cacheRequest);

        assertThat(result.status()).isEqualTo("in_progress");
        assertThat(result.jobId()).isNotBlank();
        // 캐시 사본은 이번 job_id 로 재기입되어 저장되어야 한다(원본 job_id 보존 금지).
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(draftStore).save(eq(result.jobId()), saved.capture());
        assertThat(readJobId(saved.getValue())).isEqualTo(result.jobId());
        verify(agentClient, never()).requestRecommend(any());
    }

    @Test
    void hitRecordsFinishedJobRecordSoPgFallbackAndAdminSeeIt() {
        // 캐시 히트는 agent job 이 없어 완료 이벤트가 오지 않는다. 서비스가 직접
        // in_progress → done 으로 기록해야 findDraft PG 폴백과 admin 집계가 성립한다.
        String hash = cacheKeyBuilder.hash(cacheRequest);
        when(reuseCacheStore.find(hash)).thenReturn(Optional.of("{\"places\":[]}"));
        when(reuseCacheStore.incrementHits(hash)).thenReturn(1L);

        JobAccepted result = service.createRecommendation(cacheRequest);

        // 캐시로 답한 잡은 agent 가 돌지 않았다. source 가 그것을 밝혀야 한다.
        verify(jobStore).insertInProgress(result.jobId(), "sched-9",
                RecommendJobStore.JobOrigin.cacheHit(), "서울특별시", "동작구");
        ArgumentCaptor<String> finished = ArgumentCaptor.forClass(String.class);
        verify(jobStore).recordCompletion(eq(result.jobId()), eq("done"), finished.capture(), org.mockito.ArgumentMatchers.isNull());
        assertThat(readJobId(finished.getValue())).isEqualTo(result.jobId());
    }

    @Test
    void hitLinksBackToTheJobThatFirstProducedIt() {
        // 캐시 본체는 그 결과를 처음 만든 잡의 식별자를 담고 있다. 사본은 곧바로
        // 자기 것으로 덮어쓰므로, 덮기 전에 꺼내 두지 않으면 원본을 되짚을 길이
        // 없다 — 후보와 선택 근거는 원본 잡에만 붙어 있어, 끈이 끊기면 사용자가
        // 실제로 저장한 일정이 어떤 후보에서 나왔는지 알 수 없게 된다.
        String origin = "11111111-2222-3333-4444-555555555555";
        String hash = cacheKeyBuilder.hash(cacheRequest);
        when(reuseCacheStore.find(hash))
                .thenReturn(Optional.of("{\"job_id\":\"" + origin + "\",\"places\":[]}"));
        when(reuseCacheStore.incrementHits(hash)).thenReturn(1L);

        JobAccepted result = service.createRecommendation(cacheRequest);

        verify(jobStore).insertInProgress(result.jobId(), "sched-9",
                RecommendJobStore.JobOrigin.cacheHit(origin), "서울특별시", "동작구");
        // 사본이 받은 본문에는 자기 식별자가 들어가야 한다(남의 잡에 작용 방지).
        ArgumentCaptor<String> finished = ArgumentCaptor.forClass(String.class);
        verify(jobStore).recordCompletion(eq(result.jobId()), eq("done"), finished.capture(), org.mockito.ArgumentMatchers.isNull());
        assertThat(readJobId(finished.getValue())).isEqualTo(result.jobId());
    }

    @Test
    void hitWithoutOriginInPayloadStillWorks() {
        // 계보를 못 읽어도 요청은 그대로 답해야 한다. 캐시는 최적화 경로다.
        String hash = cacheKeyBuilder.hash(cacheRequest);
        when(reuseCacheStore.find(hash)).thenReturn(Optional.of("{\"places\":[]}"));
        when(reuseCacheStore.incrementHits(hash)).thenReturn(1L);

        JobAccepted result = service.createRecommendation(cacheRequest);

        assertThat(result.jobId()).isNotBlank();
        verify(jobStore).insertInProgress(result.jobId(), "sched-9",
                RecommendJobStore.JobOrigin.cacheHit(), "서울특별시", "동작구");
    }

    @Test
    void hitAtThresholdStillReturnsImmediatelyAndTriggersBackgroundRefresh() {
        String hash = cacheKeyBuilder.hash(cacheRequest);
        when(reuseCacheStore.find(hash)).thenReturn(Optional.of("{\"places\":[]}"));
        when(reuseCacheStore.incrementHits(hash)).thenReturn(3L);
        JobAccepted backgroundJob = new JobAccepted("bg-job-1", "in_progress", 3);
        when(agentClient.requestRecommend(cacheRequest)).thenReturn(backgroundJob);

        JobAccepted result = service.createRecommendation(cacheRequest);

        assertThat(result.jobId()).isNotEqualTo("bg-job-1");
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(draftStore).save(eq(result.jobId()), saved.capture());
        assertThat(readJobId(saved.getValue())).isEqualTo(result.jobId());
        verify(agentClient, times(1)).requestRecommend(cacheRequest);
        verify(reuseCacheStore).linkJob("bg-job-1", hash);
    }

    @Test
    void cacheLookupFailureFallsBackToMiss() {
        String hash = cacheKeyBuilder.hash(cacheRequest);
        when(reuseCacheStore.find(hash)).thenThrow(new RuntimeException("redis down"));
        JobAccepted agentResult = new JobAccepted("agent-job-2", "in_progress", 3);
        when(agentClient.requestRecommend(cacheRequest)).thenReturn(agentResult);

        JobAccepted result = service.createRecommendation(cacheRequest);

        assertThat(result).isEqualTo(agentResult);
    }

    @Test
    void backgroundRefreshFailureDoesNotAffectResponse() {
        String hash = cacheKeyBuilder.hash(cacheRequest);
        when(reuseCacheStore.find(hash)).thenReturn(Optional.of("{\"places\":[]}"));
        when(reuseCacheStore.incrementHits(hash)).thenReturn(3L);
        when(agentClient.requestRecommend(cacheRequest))
                .thenThrow(new RuntimeException("agent down"));

        JobAccepted result = service.createRecommendation(cacheRequest);

        assertThat(result.status()).isEqualTo("in_progress");
        assertThat(result.jobId()).isNotBlank();
    }

    // ---- 사용자 선택 동선(stage=route) ----

    /** 사용자가 고른 장소 n 개. */
    private static java.util.List<map.service.user.recommend.dto.SelectedPlace>
            selected(int n) {
        java.util.List<map.service.user.recommend.dto.SelectedPlace> out =
                new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new map.service.user.recommend.dto.SelectedPlace(
                    "고른곳" + i, "주소", 37.5 + i * 0.01, 127.0 + i * 0.01, null, null,
                    null));
        }
        return out;
    }

    /** 장소 목록을 실은 요청(클라이언트가 stage 를 뭘 보내든 서버가 강제한다). */
    private static RecommendRequest routeRequest(String clientStage) {
        RecommendRequest base = request(clientStage, List.of("kakao:9"));
        return new RecommendRequest(
                base.date(), base.budget(), base.theme(), base.mobility(),
                base.province(), base.city(), base.scheduleId(),
                base.stage(), base.exclude(), selected(3));
    }

    @Test
    void routeJobForcesStageAndKeepsPlaces() {
        service.createRouteJob(routeRequest("init"));

        ArgumentCaptor<RecommendRequest> captor =
                ArgumentCaptor.forClass(RecommendRequest.class);
        verify(agentClient).requestRecommend(captor.capture());
        RecommendRequest sent = captor.getValue();
        assertThat(sent.stage()).isEqualTo("route");
        assertThat(sent.exclude()).isEmpty();
        assertThat(sent.places()).hasSize(3);
        assertThat(sent.places().get(0).name()).isEqualTo("고른곳0");
    }

    @Test
    void routeJobSkipsReuseCacheEntirely() {
        service.createRouteJob(routeRequest("mode1"));

        // 장소 조합이 결과를 좌우하므로 캐시를 조회하지도 등록하지도 않는다.
        verify(reuseCacheStore, never()).find(anyString());
        verify(reuseCacheStore, never()).linkJob(anyString(), anyString());
    }

    @Test
    void routeJobRecordsInProgress() {
        service.createRouteJob(routeRequest("init"));

        // 사용자가 직접 고른 경로다. 후보도 랭킹도 거치지 않으므로 나중에
        // 학습 자료를 고를 때 다른 경로와 같이 묶이면 안 된다.
        verify(jobStore).insertInProgress("job-2", "sched-1",
                RecommendJobStore.JobOrigin.agent("route"), "서울특별시", "강남구");
    }

    // ─── 저장된 취향 병합 ────────────────────────────────────────
    //
    // 가장 중요한 계약은 "취향이 없으면 도입 전과 완전히 같다" 와
    // "취향이 섞인 결과는 공용 캐시에 들어가지 않는다" 둘이다.

    private static final Long USER = 42L;

    /** 취향 병합이 관여하는 단언은 레코드 동등 스텁 대신 captor 로 본다. */
    private RecommendRequest capturedOutbound() {
        ArgumentCaptor<RecommendRequest> captor =
                ArgumentCaptor.forClass(RecommendRequest.class);
        verify(agentClient).requestRecommend(captor.capture());
        return captor.getValue();
    }

    // userId 가 없으면 요청이 그대로 나가고 취향을 조회조차 하지 않는다
    @Test
    void nullUserIdSendsOriginalRequestUnchanged() {
        String hash = cacheKeyBuilder.hash(cacheRequest);
        when(reuseCacheStore.find(hash)).thenReturn(Optional.empty());

        service.createRecommendationDetailed(cacheRequest, null);

        assertThat(capturedOutbound()).isEqualTo(cacheRequest);
        verify(profileThemeProvider, never()).themesFor(any());
        verify(reuseCacheStore).linkJob("job-2", hash);
    }

    // 저장된 취향이 비어 있으면 요청이 그대로 나가고 캐시에도 정상 등록된다
    @Test
    void emptyProfileSendsOriginalRequestUnchanged() {
        String hash = cacheKeyBuilder.hash(cacheRequest);
        when(reuseCacheStore.find(hash)).thenReturn(Optional.empty());
        when(profileThemeProvider.themesFor(USER)).thenReturn(List.of());

        service.createRecommendationDetailed(cacheRequest, USER);

        assertThat(capturedOutbound()).isEqualTo(cacheRequest);
        verify(reuseCacheStore).linkJob("job-2", hash);
    }

    // 병합이 일어나도 캐시는 병합 전 테마로 조회한다
    @Test
    void cacheLookupUsesOriginalThemeHashEvenWhenMerging() {
        String originalHash = cacheKeyBuilder.hash(cacheRequest);
        when(reuseCacheStore.find(originalHash)).thenReturn(Optional.empty());
        when(profileThemeProvider.themesFor(USER))
                .thenReturn(List.of("cafe", "food"));

        service.createRecommendationDetailed(cacheRequest, USER);

        // 병합된 값으로 키를 만들었다면 이 해시로는 조회하지 않았을 것이다.
        verify(reuseCacheStore).find(originalHash);
        assertThat(capturedOutbound().theme())
                .containsExactly("역사", "cafe", "food");
    }

    // 취향이 섞인 결과는 캐시에 연결하지 않는다
    @Test
    void mergedRequestIsNeverLinkedToCache() {
        String hash = cacheKeyBuilder.hash(cacheRequest);
        when(reuseCacheStore.find(hash)).thenReturn(Optional.empty());
        when(profileThemeProvider.themesFor(USER)).thenReturn(List.of("cafe"));

        service.createRecommendationDetailed(cacheRequest, USER);

        // 연결이 없으면 완료 이벤트가 와도 캐시 본체를 쓰지 않는다.
        verify(reuseCacheStore, never()).linkJob(anyString(), anyString());
    }

    // 고른 테마가 앞에 남고 중복은 빠지며 상한 6에서 멈춘다
    @Test
    void mergedThemeKeepsExplicitFirstAndCapsAtSix() {
        String hash = cacheKeyBuilder.hash(cacheRequest);
        when(reuseCacheStore.find(hash)).thenReturn(Optional.empty());
        when(profileThemeProvider.themesFor(USER)).thenReturn(
                List.of("역사", "cafe", "food", "nature", "photo", "night"));

        service.createRecommendationDetailed(cacheRequest, USER);

        assertThat(capturedOutbound().theme()).containsExactly(
                "역사", "cafe", "food", "nature", "photo", "night");
    }

    // 고른 테마가 이미 상한만큼이면 취향을 덧붙이지 않는다
    @Test
    void explicitThemesAreNeverTruncated() {
        RecommendRequest many = withTheme(cacheRequest,
                List.of("a", "b", "c", "d", "e", "f", "g"));
        String hash = cacheKeyBuilder.hash(many);
        when(reuseCacheStore.find(hash)).thenReturn(Optional.empty());
        when(profileThemeProvider.themesFor(USER)).thenReturn(List.of("cafe"));

        service.createRecommendationDetailed(many, USER);

        assertThat(capturedOutbound().theme()).hasSize(7);
        verify(reuseCacheStore).linkJob("job-2", hash);
    }

    // 취향이 고른 테마에 이미 다 들어 있으면 병합으로 보지 않는다
    @Test
    void profileSubsetOfExplicitIsNotAMerge() {
        String hash = cacheKeyBuilder.hash(cacheRequest);
        when(reuseCacheStore.find(hash)).thenReturn(Optional.empty());
        when(profileThemeProvider.themesFor(USER)).thenReturn(List.of("역사"));

        service.createRecommendationDetailed(cacheRequest, USER);

        assertThat(capturedOutbound()).isEqualTo(cacheRequest);
        // 덧붙은 것이 없으니 캐시에 넣지 못할 이유도 없다.
        verify(reuseCacheStore).linkJob("job-2", hash);
    }

    // 캐시가 맞으면 취향을 조회하지 않는다
    @Test
    void cacheHitSkipsProfileLookupEntirely() {
        String hash = cacheKeyBuilder.hash(cacheRequest);
        when(reuseCacheStore.find(hash))
                .thenReturn(Optional.of("{\"places\":[]}"));
        when(reuseCacheStore.incrementHits(hash)).thenReturn(1L);

        service.createRecommendationDetailed(cacheRequest, USER);

        verify(profileThemeProvider, never()).themesFor(any());
        verify(agentClient, never()).requestRecommend(any());
    }

    // 배경 갱신은 병합 전 테마로 나간다
    @Test
    void backgroundRefreshUsesOriginalThemeNotMergedTheme() {
        String hash = cacheKeyBuilder.hash(cacheRequest);
        when(reuseCacheStore.find(hash))
                .thenReturn(Optional.of("{\"places\":[]}"));
        when(reuseCacheStore.incrementHits(hash)).thenReturn(3L);
        when(profileThemeProvider.themesFor(USER)).thenReturn(List.of("cafe"));

        service.createRecommendationDetailed(cacheRequest, USER);

        // 갱신이 병합본으로 나가면 그 결과가 공용 해시에 저장되어
        // 무관한 사용자 전원에게 특정인 취향이 서빙된다.
        assertThat(capturedOutbound()).isEqualTo(cacheRequest);
        verify(reuseCacheStore).linkJob("job-2", hash);
    }

    // 취향 조회가 실패해도 추천은 원본 요청으로 진행한다
    @Test
    void profileLookupFailureFallsBackToUnmergedRequest() {
        String hash = cacheKeyBuilder.hash(cacheRequest);
        when(reuseCacheStore.find(hash)).thenReturn(Optional.empty());
        when(profileThemeProvider.themesFor(USER))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(() ->
                service.createRecommendationDetailed(cacheRequest, USER))
                .doesNotThrowAnyException();

        assertThat(capturedOutbound()).isEqualTo(cacheRequest);
        verify(reuseCacheStore).linkJob("job-2", hash);
    }

    private static RecommendRequest withTheme(
            RecommendRequest base, List<String> theme) {
        return new RecommendRequest(
                base.date(), base.budget(), theme, base.mobility(),
                base.province(), base.city(), base.scheduleId(),
                base.stage(), base.exclude(), base.places());
    }

    // ---- 날씨 변화 재추천(캐시 우회) ----

    @Test
    void freshRecommendationSkipsReuseCacheEntirely() {
        // 캐시에 같은 조건의 결과가 있어도 그것을 쓰면 안 된다. 날씨가 바뀌어
        // 다시 짜는 요청인데 캐시가 맞으면 agent 가 아예 돌지 않고, 그러면
        // 비 오기 전에 만든 그 야외 코스가 그대로 돌아온다.
        when(reuseCacheStore.find(anyString()))
                .thenReturn(Optional.of("{\"job_id\":\"old\",\"places\":[]}"));

        JobAccepted accepted =
                service.createFreshRecommendation(request("init", List.of()));

        assertThat(accepted.jobId()).isEqualTo("job-2");
        verify(agentClient).requestRecommend(any());
        verify(reuseCacheStore, never()).find(anyString());
        // 등록도 하지 않는다 — 비 오는 날 만든 코스가 맑은 날 요청의 캐시로
        // 재사용되면 반대 방향으로 같은 사고가 난다.
        verify(reuseCacheStore, never()).linkJob(anyString(), anyString());
    }

    @Test
    void freshRecommendationForcesInitStageAndRecordsRegion() {
        service.createFreshRecommendation(request("mode1", List.of("cheat")));

        ArgumentCaptor<RecommendRequest> captor =
                ArgumentCaptor.forClass(RecommendRequest.class);
        verify(agentClient).requestRecommend(captor.capture());
        assertThat(captor.getValue().stage()).isEqualTo("init");
        assertThat(captor.getValue().exclude()).isEmpty();
        verify(jobStore).insertInProgress(eq("job-2"), eq("sched-1"), any(RecommendJobStore.JobOrigin.class), eq("서울특별시"), eq("강남구"));
    }

    @Test
    @DisplayName("같은 조건이 겹치면 agent 를 다시 부르지 않는다")
    void 겹치는_요청은_agent_를_부르지_않는다() {
        // 한 건이 LLM 을 두 번 쓴다. 같은 조건 열 건이 동시에 들어오면
        // 스무 번이 나가는데, 하루 한도가 정해진 자원이라 그 낭비가 크다.
        when(reuseCacheStore.claimProducer(anyString(), anyString())).thenReturn("existing-generation");

        RecommendService.RecommendationResult result =
                service.createRecommendationDetailed(request("init", List.of()), null);

        verify(agentClient, never()).requestRecommend(any());
        assertThat(result.accepted().jobId()).isNotBlank();
    }

    @Test
    @DisplayName("붙는 쪽도 자기 job_id 를 갖는다")
    void 붙는_쪽도_자기_job_id_를_갖는다() {
        // 앞선 요청의 job 을 그대로 주면, 한 사람이 고친 것이 다른 사람 결과를
        // 바꾼다. 초안은 고칠 수 있는 값이므로 반드시 따로 가져가야 한다.
        when(reuseCacheStore.claimProducer(anyString(), anyString())).thenReturn("existing-generation");

        RecommendService.RecommendationResult first =
                service.createRecommendationDetailed(request("init", List.of()), null);
        RecommendService.RecommendationResult second =
                service.createRecommendationDetailed(request("init", List.of()), null);

        assertThat(first.accepted().jobId()).isNotEqualTo(second.accepted().jobId());
        // 앞서 만드는 쪽이 받은 job_id 와도 달라야 한다.
        assertThat(first.accepted().jobId()).isNotEqualTo("job-2");
    }

    @Test
    @DisplayName("취향이 섞인 요청은 겹쳐도 따로 만든다")
    void 취향이_섞이면_묶지_않는다() {
        // 해시는 취향을 섞기 전 값으로 만들어져, 취향이 다른 두 사람이 같은
        // 해시를 만든다. 그 상태로 묶으면 뒤에 온 사람이 앞사람 취향이 반영된
        // 결과를 받는다.
        when(profileThemeProvider.themesFor(any())).thenReturn(List.of("맛집"));
        when(reuseCacheStore.claimProducer(anyString(), anyString())).thenReturn("existing-generation");

        service.createRecommendationDetailed(request("init", List.of()), 42L);

        verify(agentClient).requestRecommend(any());
    }

    @Test
    @DisplayName("앞선 요청이 끝나면 붙어 있던 job 도 그 결과로 답한다")
    void 붙어있던_job_이_결과를_받는다() {
        when(reuseCacheStore.claimProducer(anyString(), anyString())).thenReturn("existing-generation");
        RecommendService.RecommendationResult joined =
                service.createRecommendationDetailed(request("init", List.of()), null);
        String jobId = joined.accepted().jobId();

        // 아직 안 나왔으면 진행 중이다.
        when(reuseCacheStore.waitingHash(jobId)).thenReturn(Optional.of("hash-x"));
        when(reuseCacheStore.find("hash-x")).thenReturn(Optional.empty());
        assertThat(service.findDraft(jobId)).isEmpty();

        // 나오면 자기 job_id 로 바꿔 받는다.
        when(reuseCacheStore.find("hash-x"))
                .thenReturn(Optional.of("{\"job_id\":\"job-2\",\"places\":[]}"));
        Optional<String> resolved = service.findDraft(jobId);

        assertThat(resolved).isPresent();
        assertThat(readJobId(resolved.get())).isEqualTo(jobId);
        verify(reuseCacheStore).clearWaiting(jobId);
    }
    @Test
    void legacyFailedPayloadCannotExposeProviderText() throws Exception {
        when(jobStore.findFinishedPayload("job-2")).thenReturn(Optional.of(
                "{\"job_id\":\"job-2\",\"status\":\"failed\",\"error\":\"private-provider-token\",\"extra\":\"private-body\"}"));
        String payload = service.findDraft("job-2").orElseThrow();
        assertThat(payload).doesNotContain("private-");
        assertThat(objectMapper.readTree(payload).path("code").asText()).isEqualTo("generation_failed");
        assertThat(objectMapper.readTree(payload).path("retryable").asBoolean()).isFalse();
    }

    @Test
    void followerReceivesOnlyItsGenerationFailure() throws Exception {
        when(reuseCacheStore.waitingHash("waiter")).thenReturn(Optional.of("v1:generation-one"));
        when(reuseCacheStore.completion("generation-one")).thenReturn(Optional.of(new ReuseCacheStore.Completion("producer-one",
                "{\"job_id\":\"producer-one\",\"status\":\"failed\",\"code\":\"no_matching_places\",\"retryable\":false}")));
        String payload = service.findDraft("waiter").orElseThrow();
        assertThat(objectMapper.readTree(payload).path("job_id").asText()).isEqualTo("waiter");
        assertThat(objectMapper.readTree(payload).path("code").asText()).isEqualTo("no_matching_places");
        verify(jobStore).finishWaiting(eq("waiter"), eq("producer-one"), eq("failed"), anyString(), anyString());
        verify(reuseCacheStore, never()).find(anyString());
        verify(reuseCacheStore).clearWaiting("waiter");
    }

    @Test
    void followerDoesNotConsumeAnOlderGenerationCache() {
        when(reuseCacheStore.waitingHash("waiter")).thenReturn(Optional.of("v1:new-generation"));
        when(reuseCacheStore.completion("new-generation")).thenReturn(Optional.empty());
        assertThat(service.findDraft("waiter")).isEmpty();
        verify(reuseCacheStore, never()).find(anyString());
    }

    @Test
    void agentSubmissionFailureReleasesOnlyOwnedGeneration() {
        when(agentClient.requestRecommend(any())).thenThrow(new IllegalStateException("private-url"));
        assertThatThrownBy(() -> service.createRecommendationDetailed(cacheRequest, null)).isInstanceOf(IllegalStateException.class);
        verify(reuseCacheStore).failProducer(anyString(), anyString());
    }

    @Test
    void cancelledProducerCannotExposeItsDeletedResultToFollower() throws Exception {
        when(reuseCacheStore.waitingHash("waiter")).thenReturn(Optional.of("v1:generation-one"));
        when(reuseCacheStore.completion("generation-one")).thenReturn(Optional.of(new ReuseCacheStore.Completion("cancelled-job", "{\"status\":\"done\",\"places\":[]}")));
        when(jobStore.isCancelled("cancelled-job")).thenReturn(true);
        String payload = service.findDraft("waiter").orElseThrow();
        assertThat(objectMapper.readTree(payload).path("code").asText()).isEqualTo("generation_failed");
        verify(jobStore, never()).findFinishedPayload("cancelled-job");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "[]", "invalid-json"})
    void malformedResultIsSafeTerminalFailure(String raw) throws Exception {
        when(jobStore.findFinishedPayload("job-2")).thenReturn(Optional.of(raw));
        String payload = service.findDraft("job-2").orElseThrow();
        assertThat(objectMapper.readTree(payload).path("status").asText()).isEqualTo("failed");
        assertThat(objectMapper.readTree(payload).path("code").asText()).isEqualTo("generation_failed");
    }

    @Test
    void legacyInflightMarkerUsesLegacySuccessLookup() {
        when(reuseCacheStore.claimProducer(anyString(), anyString())).thenReturn("1");
        service.createRecommendationDetailed(cacheRequest, null);
        verify(reuseCacheStore).markLegacyWaiting(anyString(), anyString());
        verify(reuseCacheStore, never()).markWaiting(anyString(), anyString());
    }

}

package map.service.user.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.recommend.dto.DateRange;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.Mobility;
import map.service.user.recommend.dto.RecommendRequest;
import org.junit.jupiter.api.BeforeEach;
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
        service = new RecommendService(
                agentClient, draftStore, objectMapper, researchLimitService, jobStore,
                reuseCacheStore, cacheKeyBuilder, immediateExecutor, 3L);
        when(agentClient.requestRecommend(any()))
                .thenReturn(new JobAccepted("job-2", "in_progress", 3));
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

        // 접수 직후 발급된 job_id 와 scheduleId 로 in_progress write-through.
        verify(jobStore).insertInProgress("job-2", "sched-1");
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
        verify(draftStore).delete("job-1");
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

    // ---- draft 조회 (Redis → PG 폴백) ----

    @Test
    void findDraftRedisHitReturnsWithoutPgFallback() {
        when(draftStore.find("job-1")).thenReturn(Optional.of("{\"a\":1}"));

        Optional<String> result = service.findDraft("job-1");

        assertThat(result).contains("{\"a\":1}");
        verify(jobStore, never()).findFinishedPayload(anyString());
    }

    @Test
    void findDraftRedisMissPgHitReturnsPayloadAndRewarms() {
        when(draftStore.find("job-1")).thenReturn(Optional.empty());
        when(jobStore.findFinishedPayload("job-1"))
                .thenReturn(Optional.of("{\"status\":\"done\"}"));

        Optional<String> result = service.findDraft("job-1");

        assertThat(result).contains("{\"status\":\"done\"}");
        // Redis 재적재(re-warm) 확인.
        verify(draftStore).save("job-1", "{\"status\":\"done\"}");
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

        verify(jobStore).insertInProgress(result.jobId(), "sched-9");
        ArgumentCaptor<String> finished = ArgumentCaptor.forClass(String.class);
        verify(jobStore).markFinished(eq(result.jobId()), eq("done"), finished.capture());
        assertThat(readJobId(finished.getValue())).isEqualTo(result.jobId());
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
                    "고른곳" + i, "주소", 37.5 + i * 0.01, 127.0 + i * 0.01, null, null));
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

        verify(jobStore).insertInProgress("job-2", "sched-1");
    }
}

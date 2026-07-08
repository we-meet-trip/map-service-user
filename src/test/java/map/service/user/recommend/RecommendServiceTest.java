package map.service.user.recommend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.recommend.dto.DateRange;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.RecommendRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * RecommendServiceTest — B2 확장 계약(stage/exclude)의 서버측 강제 검증
 *
 * create: 클라이언트 stage/exclude 무시하고 init/[] 강제.
 * research: draft places[].content_id 수집(비-텍스트/공백/중복 제외) 후
 *           mode1 + exclude 로 재구성, draft 폐기 순서 보장.
 */
class RecommendServiceTest {

    private AgentClient agentClient;
    private DraftStore draftStore;
    private ResearchLimitService researchLimitService;
    private RecommendJobStore jobStore;
    private RecommendService service;

    @BeforeEach
    void setUp() {
        agentClient = mock(AgentClient.class);
        draftStore = mock(DraftStore.class);
        researchLimitService = mock(ResearchLimitService.class);
        jobStore = mock(RecommendJobStore.class);
        service = new RecommendService(
                agentClient, draftStore, new ObjectMapper(), researchLimitService, jobStore);
        when(agentClient.requestRecommend(any()))
                .thenReturn(new JobAccepted("job-2", "in_progress", 3));
        // 기본은 한도 이내(허용). 한도 초과 시나리오는 개별 테스트에서 재정의한다.
        when(researchLimitService.tryConsume(anyString())).thenReturn(true);
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
                exclude);
    }

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
    void createWritesInProgressJobRecord() {
        service.createRecommendation(request("init", List.of()));

        // 접수 직후 발급된 job_id 와 scheduleId 로 in_progress write-through.
        verify(jobStore).insertInProgress("job-2", "sched-1");
    }

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
}

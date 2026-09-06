package map.service.user.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import map.service.user.global.exception.CustomException;
import map.service.user.global.exception.ErrorCode;
import map.service.user.places.ReviewSummaryService;
import map.service.user.recommend.RecommendService;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.RecommendRequest;
import map.service.user.recommend.dto.SelectedPlace;
import map.service.user.trip.dto.BudgetRange;
import map.service.user.trip.dto.Location;
import map.service.user.trip.dto.Schedule;
import map.service.user.trip.dto.TripGenerateResponse;
import map.service.user.trip.dto.TripResearchRequest;
import map.service.user.trip.dto.TripStop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * TripResearchServiceTest — 같은 조건 재탐색 동기 facade
 *
 * 재탐색이 "다른 장소"를 실제로 요구하는지(제외 목록·남길 장소가 그대로
 * 실리는지), 하루 한도를 넘긴 요청이 agent 까지 가지 않는지, 결과가 생성
 * 직후 화면과 같은 형태로 나오는지를 본다.
 */
@DisplayName("TripService.research 재탐색 facade")
class TripResearchServiceTest {

    private static final String PREV_ID = "11111111-1111-1111-1111-111111111111";
    private static final String JOB_ID = "22222222-2222-2222-2222-222222222222";

    private RecommendService recommendService;
    private HubWeatherClient hubWeatherClient;
    private TripStopsAssembler stopsAssembler;
    private TripService service;

    @BeforeEach
    void setUp() {
        recommendService = mock(RecommendService.class);
        hubWeatherClient = mock(HubWeatherClient.class);
        stopsAssembler = mock(TripStopsAssembler.class);
        service = new TripService(
                recommendService, hubWeatherClient, stopsAssembler,
                mock(ReviewSummaryService.class), new ObjectMapper(),
                mock(map.service.user.schedule.ScheduleService.class), 1L, 10L);
        when(recommendService.research(anyString(), any(), any(), any(), any()))
                .thenReturn(new JobAccepted(JOB_ID, "in_progress", 3));
    }

    private static TripResearchRequest request(
            List<String> exclude, List<SelectedPlace> keep, Long scheduleId) {
        return new TripResearchRequest(
                new Schedule(
                        LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 6), 9, 18),
                new BudgetRange(50000, 150000),
                List.of("food", "nature"),
                "walk",
                new Location("강원도", "속초시"),
                PREV_ID,
                exclude,
                keep,
                scheduleId);
    }

    private void draftIsDone() {
        when(recommendService.findDraft(JOB_ID)).thenReturn(Optional.of(
                "{\"job_id\":\"" + JOB_ID + "\",\"status\":\"done\","
                        + "\"places\":[],\"visit_order\":[],\"legs\":[]}"));
    }

    private static TripStop stop(int order) {
        return new TripStop(
                order, 1, "장소" + order, "주소", "09:00", 38.19, 128.60,
                null, "kakao", null, true, order, null, null, null, null, null,
                "kakao:" + order);
    }

    @Test
    @DisplayName("이전 추천 식별자·제외 목록·남길 장소를 그대로 넘긴다")
    void forwardsPreviousTripExcludeAndKeep() {
        draftIsDone();
        when(stopsAssembler.assemble(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(stop(1)));
        SelectedPlace keep = new SelectedPlace(
                "속초해변", "강원 속초시", 38.19, 128.60, 1, "kakao:1", "해변");

        service.research(request(List.of("kakao:1", "kakao:2"), List.of(keep), null), 7L);

        ArgumentCaptor<RecommendRequest> req =
                ArgumentCaptor.forClass(RecommendRequest.class);
        ArgumentCaptor<List<String>> exclude =
                ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<SelectedPlace>> pinned =
                ArgumentCaptor.forClass(List.class);
        verify(recommendService).research(
                eq(PREV_ID), req.capture(), exclude.capture(), pinned.capture(), eq(7L));

        assertThat(exclude.getValue()).containsExactly("kakao:1", "kakao:2");
        assertThat(pinned.getValue()).hasSize(1);
        assertThat(pinned.getValue().get(0).contentId()).isEqualTo("kakao:1");
        // 조건은 처음 추천과 같은 것을 그대로 다시 싣는다.
        assertThat(req.getValue().theme()).containsExactly("food", "nature");
        assertThat(req.getValue().budget()).isNotNull();
        assertThat(req.getValue().province()).isEqualTo("강원특별자치도");
    }

    @Test
    @DisplayName("일정 식별자가 있으면 함께 넘겨 한도를 일정 단위로 세게 한다")
    void passesScheduleIdWhenPresent() {
        draftIsDone();
        when(stopsAssembler.assemble(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(stop(1)));

        service.research(request(null, null, 42L));

        ArgumentCaptor<RecommendRequest> req =
                ArgumentCaptor.forClass(RecommendRequest.class);
        verify(recommendService).research(
                eq(PREV_ID), req.capture(), any(), any(), any());
        assertThat(req.getValue().scheduleId()).isEqualTo("42");
    }

    @Test
    @DisplayName("응답은 생성 직후와 같은 형태다")
    void returnsAssembledStops() {
        draftIsDone();
        when(stopsAssembler.assemble(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(stop(1), stop(2)));

        TripGenerateResponse out = service.research(request(null, null, null));

        assertThat(out.tripId()).isEqualTo(JOB_ID);
        assertThat(out.stops()).hasSize(2);
        assertThat(out.stops().get(0).contentId()).isEqualTo("kakao:1");
    }

    @Test
    @DisplayName("하루 한도를 넘기면 409 로 끝나고 결과를 기다리지 않는다")
    void propagatesLimitExceeded() {
        when(recommendService.research(anyString(), any(), any(), any(), any()))
                .thenThrow(new CustomException(ErrorCode.RESEARCH_LIMIT_EXCEEDED));

        assertThatThrownBy(() -> service.research(request(null, null, null)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.RESEARCH_LIMIT_EXCEEDED);

        verify(recommendService, never()).findDraft(anyString());
    }

    @Test
    @DisplayName("탐색 기반 추천 경로(재사용 캐시)를 타지 않는다")
    void doesNotUseRecommendationCachePath() {
        draftIsDone();
        when(stopsAssembler.assemble(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(stop(1)));

        service.research(request(null, null, null));

        verify(recommendService, never()).createRecommendationDetailed(any(), any());
        verify(recommendService, never()).createFreshRecommendation(any());
    }

    @Test
    @DisplayName("추천이 실패로 끝나면 502 로 올린다")
    void failedDraftBecomesGenerationException() {
        when(recommendService.findDraft(JOB_ID)).thenReturn(Optional.of(
                "{\"job_id\":\"" + JOB_ID + "\",\"status\":\"failed\","
                        + "\"error\":\"no candidates\"}"));

        assertThatThrownBy(() -> service.research(request(null, null, null)))
                .isInstanceOf(TripGenerationException.class);

        verify(stopsAssembler, never())
                .assemble(any(), anyString(), anyInt(), anyInt());
    }
}

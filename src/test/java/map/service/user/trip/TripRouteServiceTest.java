package map.service.user.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import map.service.user.places.ReviewSummaryService;
import map.service.user.recommend.RecommendService;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.RecommendRequest;
import map.service.user.recommend.dto.SelectedPlace;
import map.service.user.trip.dto.Location;
import map.service.user.trip.dto.BudgetRange;
import map.service.user.trip.dto.Schedule;
import map.service.user.trip.dto.TripGenerateRequest;
import map.service.user.trip.dto.TripGenerateResponse;
import map.service.user.trip.dto.TripResearchRequest;
import map.service.user.trip.dto.TripRouteRequest;
import map.service.user.trip.dto.TripStop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * TripRouteServiceTest — 고른 장소 동선 동기 facade
 *
 * 결과가 생성 직후 화면과 같은 형태(방문 시각·이동 카드·폴리라인이 붙은
 * stops[])로 나오는지, 실패한 추천이 502 로 이어지는지, 재사용 캐시를 타지
 * 않는지를 본다.
 */
@DisplayName("TripService.route 동선 facade")
class TripRouteServiceTest {

    private static final String JOB_ID = "22222222-2222-2222-2222-222222222222";

    private RecommendService recommendService;
    private HubWeatherClient hubWeatherClient;
    private TripStopsAssembler stopsAssembler;
    private ReviewSummaryService reviewSummaryService;
    private TripService service;

    @BeforeEach
    void setUp() {
        recommendService = mock(RecommendService.class);
        hubWeatherClient = mock(HubWeatherClient.class);
        stopsAssembler = mock(TripStopsAssembler.class);
        reviewSummaryService = mock(ReviewSummaryService.class);
        service = new TripService(
                recommendService, hubWeatherClient, stopsAssembler,
                reviewSummaryService, new ObjectMapper(),
                mock(map.service.user.schedule.ScheduleService.class), 1L, 10L);
        when(recommendService.createRouteJob(any(), any()))
                .thenReturn(new JobAccepted(JOB_ID, "in_progress", 3));
    }

    private static TripRouteRequest request() {
        return new TripRouteRequest(
                new Schedule(
                        LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 6), 9, 18),
                "walk",
                new Location("강원도", "속초시"),
                List.of(
                        new SelectedPlace("속초해변", "강원 속초시", 38.19, 128.60, 1, "kakao:1",
                                "여행 / 관광,명소 / 해수욕장,해변"),
                        new SelectedPlace("영금정", null, 38.21, 128.60, null, null, null)));
    }

    private void draftIsDone() {
        when(recommendService.findDraft(JOB_ID)).thenReturn(Optional.of(
                "{\"job_id\":\"" + JOB_ID + "\",\"status\":\"done\","
                        + "\"places\":[],\"visit_order\":[],\"legs\":[]}"));
    }

    private static TripStop stop(int order, Integer durationMinutes) {
        return new TripStop(
                order, 1, "장소" + order, "주소", "09:00", 38.19, 128.60,
                durationMinutes == null ? null
                        : new map.service.user.trip.dto.TransportToNext(
                                "walk", "이동: 도보", durationMinutes, 1.2,
                                List.of(List.of(38.19, 128.60))),
                null, null, true, order, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("고른 장소를 그대로 실어 동선 작업을 만든다")
    void forwardsSelectedPlaces() {
        draftIsDone();
        when(stopsAssembler.assemble(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(stop(1, null)));

        service.route(request());

        ArgumentCaptor<RecommendRequest> captor =
                ArgumentCaptor.forClass(RecommendRequest.class);
        verify(recommendService).createRouteJob(captor.capture(), any());
        RecommendRequest sent = captor.getValue();
        assertThat(sent.places()).hasSize(2);
        assertThat(sent.places().get(0).name()).isEqualTo("속초해변");
        // 장소가 정해진 요청이라 후보 선별 조건은 보내지 않는다.
        assertThat(sent.budget()).isNull();
        assertThat(sent.theme()).isNull();
    }

    @Test
    @DisplayName("응답은 생성 직후와 같은 형태 — 이동 카드와 폴리라인이 붙는다")
    void returnsAssembledStops() {
        draftIsDone();
        when(stopsAssembler.assemble(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(stop(1, 15), stop(2, null)));

        TripGenerateResponse out = service.route(request());

        assertThat(out.tripId()).isEqualTo(JOB_ID);
        assertThat(out.stops()).hasSize(2);
        assertThat(out.stops().get(0).transportToNext().path()).isNotEmpty();
        assertThat(out.totalDurationMinutes()).isEqualTo(15);
    }

    @Test
    @DisplayName("draft 의 warnings 와 timeline_status 를 응답에 그대로 싣는다")
    void passesThroughWarningsAndTimelineStatus() {
        when(recommendService.findDraft(JOB_ID)).thenReturn(Optional.of(
                "{\"job_id\":\"" + JOB_ID + "\",\"status\":\"done\","
                        + "\"places\":[],\"visit_order\":[],\"legs\":[],"
                        + "\"timeline_status\":\"unverified\","
                        + "\"warnings\":[\"날씨 정보를 확인하지 못해 일정에 반영하지 못했습니다\"]}"));
        when(stopsAssembler.assemble(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(stop(1, null)));

        TripGenerateResponse out = service.route(request());

        assertThat(out.warnings())
                .containsExactly("날씨 정보를 확인하지 못해 일정에 반영하지 못했습니다");
        assertThat(out.timelineStatus()).isEqualTo("unverified");
    }

    @Test
    @DisplayName("warnings 가 없는 draft 는 응답에도 warnings 가 없다")
    void omitsWarningsWhenDraftHasNone() {
        draftIsDone();
        when(stopsAssembler.assemble(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(stop(1, null)));

        TripGenerateResponse out = service.route(request());

        assertThat(out.warnings()).isNull();
        assertThat(out.timelineStatus()).isNull();
    }

    @Test
    @DisplayName("탐색 기반 추천 경로(재사용 캐시)를 타지 않는다")
    void doesNotUseRecommendationCachePath() {
        draftIsDone();
        when(stopsAssembler.assemble(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(stop(1, null)));

        service.route(request());

        verify(recommendService, never()).createRecommendationDetailed(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("수동 동선과 명시적 최적화는 외부 AI 장소 요약을 예약하지 않는다")
    void routeNeverPrewarmsGenerativeSummaries(boolean optimize) {
        draftIsDone();
        List<TripStop> stops = List.of(stop(1, 15), stop(2, null));
        when(stopsAssembler.assemble(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(stops);
        TripRouteRequest original = request();
        // false case exercises the default constructor used by manual ordering.
        TripRouteRequest input = optimize
                ? new TripRouteRequest(original.schedule(), original.transport(),
                        original.location(), original.places(), true)
                : original;

        TripGenerateResponse out = service.route(input, 7L);

        verifyNoInteractions(reviewSummaryService);
        ArgumentCaptor<RecommendRequest> sent = ArgumentCaptor.forClass(RecommendRequest.class);
        verify(recommendService).createRouteJob(sent.capture(), org.mockito.ArgumentMatchers.eq(7L));
        assertThat(sent.getValue().optimize()).isEqualTo(optimize);
        assertThat(sent.getValue().places()).containsExactlyElementsOf(original.places());
        assertThat(out.stops()).containsExactlyElementsOf(stops);
        assertThat(out.totalDurationMinutes()).isEqualTo(15);
        verify(hubWeatherClient).fetchWeather("강원특별자치도", "속초시",
                original.schedule().startDate(), original.schedule().endDate());
    }

    @Test
    @DisplayName("자동 추천은 별도 리뷰 요약 동의를 추정하지 않는다")
    void generateDoesNotInferReviewSummaryConsent() {
        draftIsDone();
        when(stopsAssembler.assemble(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(stop(1, 15), stop(2, null)));
        when(recommendService.createRecommendationDetailed(any(), any()))
                .thenReturn(new RecommendService.RecommendationResult(
                        new JobAccepted(JOB_ID, "in_progress", 3), false));
        TripRouteRequest original = request();

        service.generate(new TripGenerateRequest(original.schedule(),
                new BudgetRange(50000, 150000), List.of("nature"),
                original.transport(), original.location()), 7L);

        verifyNoInteractions(reviewSummaryService);
    }

    @Test
    @DisplayName("재탐색은 별도 리뷰 요약 동의를 추정하지 않는다")
    void researchDoesNotInferReviewSummaryConsent() {
        draftIsDone();
        when(stopsAssembler.assemble(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(stop(1, 15), stop(2, null)));
        when(recommendService.research(anyString(), any(), any(), any(), any()))
                .thenReturn(new JobAccepted(JOB_ID, "in_progress", 3));
        TripRouteRequest original = request();

        service.research(new TripResearchRequest(original.schedule(),
                new BudgetRange(50000, 150000), List.of("nature"),
                original.transport(), original.location(), JOB_ID,
                List.of(), List.of(), null), 7L);

        verifyNoInteractions(reviewSummaryService);
    }

    @Test
    @DisplayName("추천이 실패로 끝나면 생성과 같은 예외로 502 가 된다")
    void failedDraftRaisesGenerationException() {
        when(recommendService.findDraft(JOB_ID)).thenReturn(Optional.of(
                "{\"job_id\":\"" + JOB_ID + "\",\"status\":\"failed\","
                        + "\"error\":\"stage=route requires places\"}"));

        assertThatThrownBy(() -> service.route(request()))
                .isInstanceOf(TripGenerationException.class)
                .hasMessageContaining("stage=route requires places");
    }

    @Test
    @DisplayName("draft 가 오지 않으면 시간초과 예외")
    void missingDraftRaisesTimeout() {
        when(recommendService.findDraft(JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.route(request()))
                .isInstanceOf(TripTimeoutException.class);
    }
}

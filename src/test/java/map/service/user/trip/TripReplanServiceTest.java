package map.service.user.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import map.service.user.places.ReviewSummaryService;
import map.service.user.recommend.DraftStore;
import map.service.user.recommend.RecommendService;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.Mobility;
import map.service.user.recommend.dto.RecommendRequest;
import map.service.user.schedule.ScheduleReplanSpec;
import map.service.user.schedule.ScheduleReplanUnavailableException;
import map.service.user.schedule.ScheduleService;
import map.service.user.trip.dto.TripGenerateResponse;
import map.service.user.trip.dto.TripStop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * TripReplanServiceTest — 저장된 일정의 동기 재추천
 *
 * 앱에는 job 폴링 코드가 없다. 추천은 /api/v1/trip/generate 한 번으로 완성된
 * 일정을 받는 동기 계약뿐이라, 날씨 재추천도 같은 형태여야 앱이 기존 결과
 * 화면을 그대로 쓴다.
 *
 * 여기서 보는 것: 저장된 조건이 추천 요청으로 옮겨지는가, 캐시를 건너뛰는가,
 * 성공했을 때만 일정의 기준선이 갱신되는가.
 */
@DisplayName("TripService.replan 동기 재추천")
class TripReplanServiceTest {

    private static final String JOB_ID = "33333333-3333-3333-3333-333333333333";
    private static final LocalDate D1 = LocalDate.of(2026, 9, 10);

    private RecommendService recommendService;
    private DraftStore draftStore;
    private HubWeatherClient hubWeatherClient;
    private TripStopsAssembler stopsAssembler;
    private ScheduleService scheduleService;
    private TripService service;

    @BeforeEach
    void setUp() {
        recommendService = mock(RecommendService.class);
        draftStore = mock(DraftStore.class);
        hubWeatherClient = mock(HubWeatherClient.class);
        stopsAssembler = mock(TripStopsAssembler.class);
        scheduleService = mock(ScheduleService.class);
        service = new TripService(
                recommendService, draftStore, hubWeatherClient, stopsAssembler,
                mock(ReviewSummaryService.class), new ObjectMapper(),
                scheduleService, 5L, 10L);

        when(scheduleService.replanSpec(5L, 42L)).thenReturn(new ScheduleReplanSpec(
                5L, "서울특별시", "중구", D1, D1, "bicycle", 9, 18));
        when(recommendService.createFreshRecommendation(any()))
                .thenReturn(new JobAccepted(JOB_ID, "in_progress", 3));
        when(draftStore.find(JOB_ID)).thenReturn(Optional.of(
                "{\"job_id\":\"" + JOB_ID + "\",\"status\":\"done\",\"places\":[]}"));
        when(stopsAssembler.assemble(any(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(hubWeatherClient.fetchWeather(any(), any(), any(), any()))
                .thenReturn(new map.service.user.trip.dto.HubWeatherResponse(
                        "서울특별시", "중구", List.of(), List.of()));
    }

    @Test
    @DisplayName("저장된 조건으로 캐시 없이 추천을 다시 돌려 완성된 일정을 돌려준다")
    void replanReturnsCompletedTrip() {
        TripGenerateResponse out = service.replan(5L, 42L);

        assertThat(out.tripId()).isEqualTo(JOB_ID);
        ArgumentCaptor<RecommendRequest> captor =
                ArgumentCaptor.forClass(RecommendRequest.class);
        verify(recommendService).createFreshRecommendation(captor.capture());
        RecommendRequest sent = captor.getValue();
        assertThat(sent.province()).isEqualTo("서울특별시");
        assertThat(sent.city()).isEqualTo("중구");
        assertThat(sent.date().dateStart()).isEqualTo(D1);
        assertThat(sent.mobility()).isEqualTo(Mobility.BICYCLE);
        assertThat(sent.scheduleId()).isEqualTo("5");
    }

    @Test
    @DisplayName("성공한 뒤에만 일정의 기준선을 갱신한다")
    void marksReplannedOnlyAfterSuccess() {
        service.replan(5L, 42L);

        verify(scheduleService).markReplanned(5L, 42L);
    }

    @Test
    @DisplayName("추천이 실패하면 기준선을 건드리지 않는다")
    void keepsBaselineWhenRecommendationFails() {
        when(draftStore.find(JOB_ID)).thenReturn(Optional.of(
                "{\"job_id\":\"" + JOB_ID + "\",\"status\":\"failed\","
                        + "\"error\":\"gemini down\"}"));

        assertThatThrownBy(() -> service.replan(5L, 42L))
                .isInstanceOf(TripGenerationException.class);
        // 실패한 재추천으로 알림을 지우면 사용자는 바뀐 날씨를 모른 채
        // 옛 코스를 그대로 들고 간다.
        verify(scheduleService, never()).markReplanned(any(), any());
    }

    @Test
    @DisplayName("다시 짤 수 없는 일정이면 추천을 시작하지 않는다")
    void rejectsUnusableScheduleBeforeCallingAgent() {
        when(scheduleService.replanSpec(9L, 42L))
                .thenThrow(new ScheduleReplanUnavailableException(9L, "이미 지나간 일정"));

        assertThatThrownBy(() -> service.replan(9L, 42L))
                .isInstanceOf(ScheduleReplanUnavailableException.class);
        verify(recommendService, never()).createFreshRecommendation(any());
    }
}

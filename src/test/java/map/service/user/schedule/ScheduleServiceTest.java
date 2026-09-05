package map.service.user.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import map.service.user.chat.repository.ChatRoomRepository;
import map.service.user.global.crypto.TestPayloadCiphers;
import map.service.user.recommend.DraftStore;
import map.service.user.recommend.RecommendJobStore;
import map.service.user.recommend.RecommendService;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.Mobility;
import map.service.user.recommend.dto.RecommendRequest;
import map.service.user.schedule.dto.ScheduleReviseRequest;
import map.service.user.trip.TripStopsAssembler;
import map.service.user.weather.ScheduleWeatherService;
import map.service.user.weather.dto.WeatherSnapshotItem;

/**
 * ScheduleServiceTest — draft 영속화 및 소유자(userId) 기록 검증
 *
 * persist 가 @AuthenticationPrincipal 로 넘어온 userId 를 ScheduleEntity 에
 * 그대로 저장하는지 확인한다.
 *
 * 소유자 없는 저장을 막는 것은 서비스가 아니라 HTTP 진입점의 몫이라
 * (ScheduleController.save 가 401 로 되돌린다) 여기서는 매핑만 본다.
 */
@DisplayName("ScheduleService 단위 테스트")
class ScheduleServiceTest {

    private static final String JOB_ID = "11111111-1111-1111-1111-111111111111";

    private DraftStore draftStore;
    private ScheduleRepository repository;
    private RecommendJobStore jobStore;
    private ScheduleWeatherService weatherService;
    private RecommendService recommendService;
    private ScheduleService service;

    @BeforeEach
    void setUp() {
        draftStore = mock(DraftStore.class);
        repository = mock(ScheduleRepository.class);
        jobStore = mock(RecommendJobStore.class);
        weatherService = mock(ScheduleWeatherService.class);
        recommendService = mock(RecommendService.class);
        service = new ScheduleService(draftStore, recommendService, repository,
                mock(ChatRoomRepository.class), mock(ScheduleArrivalRepository.class),
                new ObjectMapper(), mock(TripStopsAssembler.class),
                TestPayloadCiphers.enabled(), jobStore, weatherService);
        // 저장은 조회와 같은 길로 초안을 찾는다(초안이 없으면 완료 기록으로 내려간다).
        when(recommendService.findDraft(JOB_ID))
                .thenReturn(Optional.of("{\"job_id\":\"" + JOB_ID + "\",\"places\":[]}"));
        when(jobStore.findRegion(JOB_ID)).thenReturn(Optional.empty());
    }

    private static ScheduleSaveRequest request() {
        return new ScheduleSaveRequest(
                JOB_ID, "제주 여행",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 7),
                "walk", 9, 18);
    }

    @Test
    @DisplayName("persist — 인증된 userId 를 ScheduleEntity 에 저장")
    void persistStoresAuthenticatedUserId() {
        service.persist(request(), 42L);

        ArgumentCaptor<ScheduleEntity> captor = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(42L);
        verify(draftStore).delete(JOB_ID);
    }

    @Test
    @DisplayName("persist — 추천 작업의 지역과 그 시점 예보를 일정에 새긴다")
    void persistStoresRegionAndWeatherBaseline() {
        when(jobStore.findRegion(JOB_ID)).thenReturn(
                Optional.of(new RecommendJobStore.Region("서울특별시", "중구")));
        List<WeatherSnapshotItem> baseline = List.of(
                new WeatherSnapshotItem(LocalDate.of(2026, 7, 6), 20, "sunny"));
        when(weatherService.buildBaseline(
                "서울특별시", "중구",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 7)))
                .thenReturn(baseline);
        ObjectMapper jsonWithDates = new ObjectMapper()
                .registerModule(new JavaTimeModule());
        when(weatherService.toJson(baseline))
                .thenReturn(jsonWithDates.valueToTree(baseline));

        service.persist(request(), 42L);

        ArgumentCaptor<ScheduleEntity> captor =
                ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(repository).save(captor.capture());
        ScheduleEntity saved = captor.getValue();
        assertThat(saved.getProvince()).isEqualTo("서울특별시");
        assertThat(saved.getCity()).isEqualTo("중구");
        assertThat(saved.getWeatherBaseline()).isNotNull();
        assertThat(saved.getWeatherBaseline().get(0).get("pop").asInt())
                .isEqualTo(20);
    }

    @Test
    @DisplayName("persist — 지역을 모르는 작업이면 날씨를 묻지 않고 그대로 저장한다")
    void persistWithoutRegionSkipsWeather() {
        service.persist(request(), 42L);

        ArgumentCaptor<ScheduleEntity> captor =
                ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getProvince()).isNull();
        assertThat(captor.getValue().getWeatherBaseline()).isNull();
        verify(weatherService, never())
                .buildBaseline(any(), any(), any(), any());
    }

    @Test
    @DisplayName("replan — 저장된 조건으로 캐시 없이 새 추천을 띄우고 걸린 알림을 지운다")
    void replanStartsNewRecommendationAndClearsAlert() {
        LocalDate soon = LocalDate.now().plusDays(3);
        ScheduleEntity entity = new ScheduleEntity(
                42L, java.util.UUID.randomUUID(), "제주 여행",
                soon, soon.plusDays(1), null, "bicycle", 9, 18);
        entity.setRegion("서울특별시", "중구");
        when(repository.findByScheduleIdAndUserId(5L, 42L))
                .thenReturn(Optional.of(entity));
        when(recommendService.createFreshRecommendation(any()))
                .thenReturn(new JobAccepted("job-9", "in_progress", 3));

        JobAccepted accepted = service.replan(5L, 42L);

        assertThat(accepted.jobId()).isEqualTo("job-9");
        ArgumentCaptor<RecommendRequest> captor =
                ArgumentCaptor.forClass(RecommendRequest.class);
        verify(recommendService).createFreshRecommendation(captor.capture());
        RecommendRequest sent = captor.getValue();
        assertThat(sent.province()).isEqualTo("서울특별시");
        assertThat(sent.city()).isEqualTo("중구");
        assertThat(sent.date().dateStart()).isEqualTo(soon);
        assertThat(sent.mobility()).isEqualTo(Mobility.BICYCLE);
        assertThat(sent.scheduleId()).isEqualTo("5");
        // 재추천도 무시와 똑같이 기준선을 지금 예보로 옮긴다 — 옮기지 않으면
        // 다음 순회가 같은 변화를 또 잡아 배너가 30분마다 되살아난다.
        verify(weatherService).acceptCurrentForecast(entity);
    }

    @Test
    @DisplayName("replan — 지역을 모르는 일정은 다시 짤 수 없다")
    void replanRejectsScheduleWithoutRegion() {
        ScheduleEntity entity = new ScheduleEntity(
                42L, java.util.UUID.randomUUID(), "옛 일정",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 7),
                null, "walk", 9, 18);
        when(repository.findByScheduleIdAndUserId(5L, 42L))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.replan(5L, 42L))
                .isInstanceOf(ScheduleReplanUnavailableException.class);
        verify(recommendService, never()).createRecommendation(any());
    }

    @Test
    @DisplayName("persist — 저장되는 본문은 평문이 아니고, 같은 소유자로 다시 읽힌다")
    void persistSealsPayloadAndReadsItBack() {
        when(draftStore.find(JOB_ID)).thenReturn(Optional.of(
                "{\"job_id\":\"" + JOB_ID + "\",\"places\":[{\"lat\":38.1907}]}"));

        service.persist(request(), 42L);

        ArgumentCaptor<ScheduleEntity> captor = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(repository).save(captor.capture());
        ScheduleEntity saved = captor.getValue();

        // 저장 자리에 좌표가 그대로 남아 있으면 감싸기가 걸리지 않은 것이다.
        assertThat(saved.getPayload().toString()).doesNotContain("38.1907");

        // 쓸 때와 읽을 때의 자리 이름이 어긋나면 여기서 복호가 실패한다.
        // 그 어긋남은 배포 후 상세 조회에서야 드러나므로 여기서 잡는다.
        when(repository.findByScheduleIdAndUserId(7L, 42L)).thenReturn(Optional.of(saved));
        assertThat(service.detail(7L, 42L)).isNotNull();
    }

    @Test
    @DisplayName("persist — userId 가 없으면 user_id 를 null 로 매핑(차단은 컨트롤러 몫)")
    void persistWithNullUserIdStoresNull() {
        service.persist(request(), null);

        ArgumentCaptor<ScheduleEntity> captor = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
    }

    @Test
    @DisplayName("replan — 이미 지나간 일정은 다시 짤 수 없다")
    void replanRejectsPastSchedule() {
        ScheduleEntity entity = new ScheduleEntity(
                42L, java.util.UUID.randomUUID(), "지난 여행",
                LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2),
                null, "walk", 9, 18);
        entity.setRegion("서울특별시", "중구");
        when(repository.findByScheduleIdAndUserId(5L, 42L))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.replan(5L, 42L))
                .isInstanceOf(ScheduleReplanUnavailableException.class);
        verify(recommendService, never()).createFreshRecommendation(any());
    }

    @Test
    @DisplayName("dismiss — 소유자 확인 뒤 기준선 이동을 감지 서비스에 맡긴다")
    void dismissDelegatesToWeatherService() {
        ScheduleEntity entity = new ScheduleEntity(
                42L, java.util.UUID.randomUUID(), "제주 여행",
                LocalDate.now().plusDays(3), LocalDate.now().plusDays(3),
                null, "walk", 9, 18);
        entity.setRegion("서울특별시", "중구");
        when(repository.findByScheduleIdAndUserId(5L, 42L))
                .thenReturn(Optional.of(entity));

        service.dismissWeatherAlert(5L, 42L);

        verify(weatherService).acceptCurrentForecast(entity);
    }

    @Test
    @DisplayName("replanSpec — 저장된 조건을 다시 짜기용 사양으로 돌려준다")
    void replanSpecReturnsStoredConditions() {
        LocalDate soon = LocalDate.now().plusDays(3);
        ScheduleEntity entity = new ScheduleEntity(
                42L, java.util.UUID.randomUUID(), "제주 여행",
                soon, soon.plusDays(1), null, "bicycle", 9, 18);
        entity.setRegion("서울특별시", "중구");
        when(repository.findByScheduleIdAndUserId(5L, 42L))
                .thenReturn(Optional.of(entity));

        ScheduleReplanSpec spec = service.replanSpec(5L, 42L);

        assertThat(spec.scheduleId()).isEqualTo(5L);
        assertThat(spec.province()).isEqualTo("서울특별시");
        assertThat(spec.city()).isEqualTo("중구");
        assertThat(spec.dateStart()).isEqualTo(soon);
        assertThat(spec.dateEnd()).isEqualTo(soon.plusDays(1));
        assertThat(spec.transport()).isEqualTo("bicycle");
        assertThat(spec.activeStartHour()).isEqualTo(9);
        assertThat(spec.activeEndHour()).isEqualTo(18);
    }

    @Test
    @DisplayName("replanSpec — 지역을 모르거나 지나간 일정은 거절한다")
    void replanSpecRejectsUnusableSchedules() {
        LocalDate soon = LocalDate.now().plusDays(3);
        ScheduleEntity noRegion = new ScheduleEntity(
                42L, java.util.UUID.randomUUID(), "옛 일정",
                soon, soon, null, "walk", 9, 18);
        ScheduleEntity past = new ScheduleEntity(
                42L, java.util.UUID.randomUUID(), "지난 여행",
                LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 2),
                null, "walk", 9, 18);
        past.setRegion("서울특별시", "중구");
        when(repository.findByScheduleIdAndUserId(5L, 42L))
                .thenReturn(Optional.of(noRegion));
        when(repository.findByScheduleIdAndUserId(6L, 42L))
                .thenReturn(Optional.of(past));

        assertThatThrownBy(() -> service.replanSpec(5L, 42L))
                .isInstanceOf(ScheduleReplanUnavailableException.class);
        assertThatThrownBy(() -> service.replanSpec(6L, 42L))
                .isInstanceOf(ScheduleReplanUnavailableException.class);
    }

    @Test
    @DisplayName("revise — 새 동선으로 본문과 작업 식별자를 갈아 끼우고 초안을 지운다")
    void reviseReplacesItinerary() {
        String newJob = "22222222-2222-2222-2222-222222222222";
        ScheduleEntity entity = new ScheduleEntity(
                42L, java.util.UUID.fromString(JOB_ID), "제주 여행",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 7),
                null, "walk", 9, 18);
        entity.setRegion("서울특별시", "중구");
        when(repository.findByScheduleIdAndUserId(5L, 42L))
                .thenReturn(Optional.of(entity));
        when(draftStore.find(newJob)).thenReturn(Optional.of(
                "{\"job_id\":\"" + newJob + "\",\"places\":[]}"));

        service.revise(5L, 42L, new ScheduleReviseRequest(newJob, null, null, null));

        verify(repository).save(entity);
        verify(draftStore).delete(newJob);
        assertThat(entity.getJobId().toString()).isEqualTo(newJob);
        assertThat(entity.getPayload()).isNotNull();
        // 보내지 않은 조건은 저장돼 있던 값을 그대로 둔다.
        assertThat(entity.getTransport()).isEqualTo("walk");
        assertThat(entity.getActiveStartHour()).isEqualTo(9);
        // 지역과 기간이 그대로라 저장 당시 예보 기준선도 그대로 유효하다.
        assertThat(entity.getProvince()).isEqualTo("서울특별시");
    }

    @Test
    @DisplayName("revise — 바뀐 조건은 덮어쓴다")
    void reviseOverwritesGivenConditions() {
        String newJob = "22222222-2222-2222-2222-222222222222";
        ScheduleEntity entity = new ScheduleEntity(
                42L, java.util.UUID.fromString(JOB_ID), "제주 여행",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 7),
                null, "walk", 9, 18);
        when(repository.findByScheduleIdAndUserId(5L, 42L))
                .thenReturn(Optional.of(entity));
        when(draftStore.find(newJob)).thenReturn(Optional.of(
                "{\"job_id\":\"" + newJob + "\",\"places\":[]}"));

        service.revise(5L, 42L,
                new ScheduleReviseRequest(newJob, "bicycle", 10, 20));

        assertThat(entity.getTransport()).isEqualTo("bicycle");
        assertThat(entity.getActiveStartHour()).isEqualTo(10);
        assertThat(entity.getActiveEndHour()).isEqualTo(20);
    }

    @Test
    @DisplayName("revise — 남의 일정이거나 초안이 사라졌으면 404")
    void reviseRejectsForeignScheduleAndMissingDraft() {
        String newJob = "22222222-2222-2222-2222-222222222222";
        when(repository.findByScheduleIdAndUserId(5L, 42L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revise(
                5L, 42L, new ScheduleReviseRequest(newJob, null, null, null)))
                .isInstanceOf(SavedScheduleNotFoundException.class);

        ScheduleEntity entity = new ScheduleEntity(
                42L, java.util.UUID.fromString(JOB_ID), "제주 여행",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 7),
                null, "walk", 9, 18);
        when(repository.findByScheduleIdAndUserId(6L, 42L))
                .thenReturn(Optional.of(entity));
        when(draftStore.find(newJob)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revise(
                6L, 42L, new ScheduleReviseRequest(newJob, null, null, null)))
                .isInstanceOf(ScheduleNotFoundException.class);
        verify(repository, never()).save(entity);
    }

    @Test
    @DisplayName("markReplanned — 기준선을 지금 예보로 옮기고 알림을 지운다")
    void markReplannedMovesBaseline() {
        LocalDate soon = LocalDate.now().plusDays(3);
        ScheduleEntity entity = new ScheduleEntity(
                42L, java.util.UUID.randomUUID(), "제주 여행",
                soon, soon, null, "walk", 9, 18);
        entity.setRegion("서울특별시", "중구");
        when(repository.findByScheduleIdAndUserId(5L, 42L))
                .thenReturn(Optional.of(entity));

        service.markReplanned(5L, 42L);

        verify(weatherService).acceptCurrentForecast(entity);
    }
}

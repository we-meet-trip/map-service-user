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
import map.service.user.global.crypto.TestPayloadCiphers;
import map.service.user.recommend.DraftStore;
import map.service.user.recommend.RecommendJobStore;
import map.service.user.recommend.RecommendService;
import map.service.user.recommend.dto.JobAccepted;
import map.service.user.recommend.dto.Mobility;
import map.service.user.recommend.dto.RecommendRequest;
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
        service = new ScheduleService(draftStore, repository, new ObjectMapper(),
                mock(TripStopsAssembler.class), TestPayloadCiphers.enabled(),
                jobStore, weatherService, recommendService);
        when(draftStore.find(JOB_ID))
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
    @DisplayName("replan — 저장된 조건으로 새 추천을 띄우고 걸린 알림을 지운다")
    void replanStartsNewRecommendationAndClearsAlert() {
        ScheduleEntity entity = new ScheduleEntity(
                42L, java.util.UUID.randomUUID(), "제주 여행",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 7),
                null, "bicycle", 9, 18);
        entity.setRegion("서울특별시", "중구");
        when(repository.findByScheduleIdAndUserId(5L, 42L))
                .thenReturn(Optional.of(entity));
        when(recommendService.createRecommendation(any()))
                .thenReturn(new JobAccepted("job-9", "in_progress", 3));

        JobAccepted accepted = service.replan(5L, 42L);

        assertThat(accepted.jobId()).isEqualTo("job-9");
        ArgumentCaptor<RecommendRequest> captor =
                ArgumentCaptor.forClass(RecommendRequest.class);
        verify(recommendService).createRecommendation(captor.capture());
        RecommendRequest sent = captor.getValue();
        assertThat(sent.province()).isEqualTo("서울특별시");
        assertThat(sent.city()).isEqualTo("중구");
        assertThat(sent.date().dateStart()).isEqualTo(LocalDate.of(2026, 7, 6));
        assertThat(sent.mobility()).isEqualTo(Mobility.BICYCLE);
        assertThat(sent.scheduleId()).isEqualTo("5");
        verify(weatherService).clearAlert(entity);
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
    @DisplayName("persist — userId 가 없으면 user_id 를 null 로 매핑(차단은 컨트롤러 몫)")
    void persistWithNullUserIdStoresNull() {
        service.persist(request(), null);

        ArgumentCaptor<ScheduleEntity> captor = ArgumentCaptor.forClass(ScheduleEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
    }
}

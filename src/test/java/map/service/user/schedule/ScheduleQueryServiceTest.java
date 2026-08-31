package map.service.user.schedule;

import map.service.user.global.crypto.TestPayloadCiphers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import map.service.user.recommend.DraftStore;
import map.service.user.schedule.dto.ScheduleDetailResponse;
import map.service.user.schedule.dto.ScheduleListResponse;
import map.service.user.trip.TripGenerationException;
import map.service.user.trip.TripStopsAssembler;
import map.service.user.trip.dto.TripStop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ScheduleQueryServiceTest — 일정 목록·상세·삭제의 소유자 범위와 조립 규칙
 *
 * 토큰이 있을 때만 자기 일정을 볼 수 있는지, 남의 일정과 토큰 없는 요청이
 * 똑같이 404 로 막히는지, 메타가 없는 일정도 기본 시간대로 열리는지,
 * 그릴 것이 없는 payload 가 예외 대신 빈 화면이 되는지를 본다.
 */
@DisplayName("ScheduleService 조회·삭제")
class ScheduleQueryServiceTest {

    private static final Long OWNER = 7L;
    private static final Long OTHER = 9L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ScheduleRepository repository;
    private TripStopsAssembler assembler;
    private ScheduleService service;
    private map.service.user.weather.ScheduleWeatherService weatherService;

    @BeforeEach
    void setUp() {
        repository = mock(ScheduleRepository.class);
        assembler = mock(TripStopsAssembler.class);
        weatherService = mock(map.service.user.weather.ScheduleWeatherService.class);
        when(weatherService.readAlert(any())).thenReturn(java.util.Optional.empty());
        service = new ScheduleService(
                mock(DraftStore.class), repository, objectMapper, assembler,
                TestPayloadCiphers.enabled(),
                mock(map.service.user.recommend.RecommendJobStore.class),
                weatherService,
                mock(map.service.user.recommend.RecommendService.class));
    }

    private ScheduleEntity entity(
            Long userId, String transport, Integer startHour, Integer endHour
    ) {
        JsonNode payload = objectMapper.createObjectNode()
                .put("job_id", UUID.randomUUID().toString())
                .put("status", "done");
        return new ScheduleEntity(
                userId, UUID.randomUUID(), "속초 당일치기",
                LocalDate.of(2026, 7, 6), LocalDate.of(2026, 7, 6),
                payload, transport, startHour, endHour);
    }

    /** 아직 끝나지 않은 일정. 날씨 알림은 이런 일정에만 붙는다. */
    private ScheduleEntity upcoming() {
        LocalDate soon = LocalDate.now().plusDays(3);
        JsonNode payload = objectMapper.createObjectNode()
                .put("job_id", UUID.randomUUID().toString())
                .put("status", "done");
        return new ScheduleEntity(
                OWNER, UUID.randomUUID(), "다가오는 여행",
                soon, soon, payload, "walk", 9, 18);
    }

    @Test
    @DisplayName("목록 — 걸려 있는 날씨 알림을 함께 내려준다")
    void listCarriesWeatherAlert() {
        ScheduleEntity e = upcoming();
        when(repository.findByUserIdOrderByDateStartAsc(OWNER))
                .thenReturn(List.of(e));
        map.service.user.weather.dto.WeatherAlert alert =
                new map.service.user.weather.dto.WeatherAlert(
                        "rain_appeared", LocalDate.of(2026, 7, 6),
                        20, 80, "sunny", "rainy", null);
        when(weatherService.readAlert(e)).thenReturn(java.util.Optional.of(alert));

        ScheduleListResponse out = service.list(OWNER);

        assertThat(out.schedules().get(0).weatherAlert()).isEqualTo(alert);
    }

    @Test
    @DisplayName("상세 — 걸려 있는 날씨 알림을 함께 내려준다")
    void detailCarriesWeatherAlert() {
        ScheduleEntity e = upcoming();
        when(repository.findByScheduleIdAndUserId(1L, OWNER))
                .thenReturn(java.util.Optional.of(e));
        map.service.user.weather.dto.WeatherAlert alert =
                new map.service.user.weather.dto.WeatherAlert(
                        "rain_cleared", LocalDate.of(2026, 7, 6),
                        80, 10, "rainy", "sunny", null);
        when(weatherService.readAlert(e)).thenReturn(java.util.Optional.of(alert));

        assertThat(service.detail(1L, OWNER).weatherAlert()).isEqualTo(alert);
    }

    @Test
    @DisplayName("목록 — 이미 지나간 일정의 알림은 내보내지 않는다")
    void listHidesAlertOnPastSchedule() {
        // 다녀온 여행에 "비 예보로 바뀌었어요" 가 남아 있으면 사용자가 무엇을
        // 해야 하는지 알 수 없다. 데이터는 남기되 표시만 하지 않는다.
        ScheduleEntity past = entity(OWNER, "walk", 9, 18);
        when(repository.findByUserIdOrderByDateStartAsc(OWNER))
                .thenReturn(List.of(past));
        when(weatherService.readAlert(past)).thenReturn(java.util.Optional.of(
                new map.service.user.weather.dto.WeatherAlert(
                        "rain_appeared", LocalDate.of(2026, 7, 6),
                        20, 80, "sunny", "rainy", null)));

        assertThat(service.list(OWNER).schedules().get(0).weatherAlert())
                .isNull();
    }

    private static TripStop stop(int order, Integer durationMinutes) {
        return new TripStop(
                order, 1, "장소" + order, "주소", "09:00", 38.19, 128.60,
                durationMinutes == null ? null
                        : new map.service.user.trip.dto.TransportToNext(
                                "walk", "이동: 도보", durationMinutes, 1.0, null),
                "kakao", "관광", true, order, null, null, null, null, null);
    }

    // ── 목록 ──────────────────────────────────────────────

    @Test
    @DisplayName("목록 — 토큰이 있으면 그 사용자의 일정만 조회한다")
    void listScopedToOwner() {
        when(repository.findByUserIdOrderByDateStartAsc(OWNER))
                .thenReturn(List.of(entity(OWNER, "walk", 9, 18)));

        ScheduleListResponse out = service.list(OWNER);

        assertThat(out.schedules()).hasSize(1);
        assertThat(out.schedules().get(0).title()).isEqualTo("속초 당일치기");
    }

    @Test
    @DisplayName("목록 — 토큰이 없으면 조회하지 않고 빈 목록을 준다")
    void listWithoutTokenIsEmpty() {
        ScheduleListResponse out = service.list(null);

        // 소유자 없는 행들을 한 목록으로 묶으면 누구에게나 남의 일정이 보인다.
        assertThat(out.schedules()).isEmpty();
        verify(repository, never()).findByUserIdOrderByDateStartAsc(any());
    }

    @Test
    @DisplayName("목록 — 일정이 없으면 빈 배열(널 아님)")
    void listEmptyIsEmptyArray() {
        when(repository.findByUserIdOrderByDateStartAsc(OWNER)).thenReturn(List.of());

        assertThat(service.list(OWNER).schedules()).isEmpty();
    }

    // ── 상세 ──────────────────────────────────────────────

    @Test
    @DisplayName("상세 — 저장된 이동수단·활동 시간대로 조립한다")
    void detailUsesStoredMeta() {
        when(repository.findByScheduleIdAndUserId(1L, OWNER))
                .thenReturn(Optional.of(entity(OWNER, "bicycle", 10, 20)));
        when(assembler.assemble(any(), eq("bicycle"), eq(10), eq(20)))
                .thenReturn(List.of(stop(1, 12), stop(2, null)));

        ScheduleDetailResponse out = service.detail(1L, OWNER);

        assertThat(out.transport()).isEqualTo("bicycle");
        assertThat(out.stops()).hasSize(2);
        // 이동 카드가 있는 구간만 합산한다(마지막 방문지는 카드 없음).
        assertThat(out.totalDurationMinutes()).isEqualTo(12);
    }

    @Test
    @DisplayName("상세 — 메타가 없는 일정은 기본 활동 시간대로 조립한다")
    void detailFallsBackToDefaultHours() {
        when(repository.findByScheduleIdAndUserId(1L, OWNER))
                .thenReturn(Optional.of(entity(OWNER, null, null, null)));
        when(assembler.assemble(any(), eq(null),
                eq(TripStopsAssembler.DEFAULT_START_HOUR),
                eq(TripStopsAssembler.DEFAULT_END_HOUR)))
                .thenReturn(List.of(stop(1, null)));

        ScheduleDetailResponse out = service.detail(1L, OWNER);

        assertThat(out.transport()).isNull();
        assertThat(out.stops()).hasSize(1);
    }

    @Test
    @DisplayName("상세 — 그릴 방문지가 없는 payload 는 빈 stops 로 응답한다")
    void detailWithUnrenderablePayloadReturnsEmptyStops() {
        when(repository.findByScheduleIdAndUserId(1L, OWNER))
                .thenReturn(Optional.of(entity(OWNER, "walk", 9, 18)));
        when(assembler.assemble(any(), any(), anyInt(), anyInt()))
                .thenThrow(new TripGenerationException("recommendation has no places"));

        ScheduleDetailResponse out = service.detail(1L, OWNER);

        assertThat(out.stops()).isEmpty();
        assertThat(out.totalDurationMinutes()).isZero();
        // 그릴 것이 없는 화면에는 생성 당시 안내도 싣지 않는다.
        assertThat(out.warnings()).isNull();
    }

    @Test
    @DisplayName("상세 — payload 의 warnings 와 timeline_status 를 그대로 싣는다")
    void detailPassesThroughWarnings() {
        ScheduleEntity saved = entity(OWNER, "walk", 9, 18);
        ((com.fasterxml.jackson.databind.node.ObjectNode) saved.getPayload())
                .put("timeline_status", "trimmed")
                .putArray("warnings")
                .add("하루 활동 시간에 맞춰 일부 일정을 줄였습니다");
        when(repository.findByScheduleIdAndUserId(1L, OWNER))
                .thenReturn(Optional.of(saved));
        when(assembler.assemble(any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(stop(1, null)));

        ScheduleDetailResponse out = service.detail(1L, OWNER);

        assertThat(out.warnings())
                .containsExactly("하루 활동 시간에 맞춰 일부 일정을 줄였습니다");
        assertThat(out.timelineStatus()).isEqualTo("trimmed");
    }

    @Test
    @DisplayName("상세 — 남의 일정은 404")
    void detailOfOtherUserIsNotFound() {
        when(repository.findByScheduleIdAndUserId(1L, OTHER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(1L, OTHER))
                .isInstanceOf(SavedScheduleNotFoundException.class);
    }

    @Test
    @DisplayName("상세 — 토큰이 없으면 조회 없이 404")
    void detailWithoutTokenIsNotFound() {
        assertThatThrownBy(() -> service.detail(1L, null))
                .isInstanceOf(SavedScheduleNotFoundException.class);

        verify(repository, never()).findByScheduleIdAndUserId(any(), any());
    }

    @Test
    @DisplayName("삭제 — 토큰이 없으면 조회도 삭제도 하지 않는다")
    void deleteWithoutTokenIsNotFound() {
        assertThatThrownBy(() -> service.delete(1L, null))
                .isInstanceOf(SavedScheduleNotFoundException.class);

        verify(repository, never()).findByScheduleIdAndUserId(any(), any());
        verify(repository, never()).delete(any());
    }

    // ── 삭제 ──────────────────────────────────────────────

    @Test
    @DisplayName("삭제 — 소유자 일정만 지운다")
    void deleteOwnSchedule() {
        ScheduleEntity owned = entity(OWNER, "walk", 9, 18);
        when(repository.findByScheduleIdAndUserId(1L, OWNER))
                .thenReturn(Optional.of(owned));

        service.delete(1L, OWNER);

        verify(repository).delete(owned);
    }

    @Test
    @DisplayName("삭제 — 남의 일정은 404 이고 삭제도 일어나지 않는다")
    void deleteOfOtherUserIsNotFound() {
        when(repository.findByScheduleIdAndUserId(1L, OTHER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(1L, OTHER))
                .isInstanceOf(SavedScheduleNotFoundException.class);
        verify(repository, never()).delete(any());
    }

    // ── 시작 ──────────────────────────────────────────────

    @Test
    @DisplayName("시작 — 처음 시작하면 시각을 새기고 상세를 돌려준다")
    void startMarksAndReturnsDetail() {
        ScheduleEntity owned = entity(OWNER, "walk", 9, 18);
        when(repository.findByScheduleIdAndUserId(1L, OWNER))
                .thenReturn(Optional.of(owned));
        when(assembler.assemble(any(), eq("walk"), anyInt(), anyInt()))
                .thenReturn(List.of(stop(1, 10), stop(2, null)));

        ScheduleDetailResponse response = service.start(1L, OWNER);

        assertThat(owned.getStartedAt()).isNotNull();
        assertThat(response.startedAt()).isEqualTo(owned.getStartedAt());
        assertThat(response.stops()).hasSize(2);
        verify(repository).save(owned);
    }

    @Test
    @DisplayName("시작 — 두 번째부터는 시각을 덮지 않고 저장도 하지 않는다")
    void startIsIdempotent() {
        ScheduleEntity owned = entity(OWNER, "walk", 9, 18);
        owned.markStarted(java.time.OffsetDateTime.parse("2026-08-01T09:00:00Z"));
        when(repository.findByScheduleIdAndUserId(1L, OWNER))
                .thenReturn(Optional.of(owned));
        when(assembler.assemble(any(), eq("walk"), anyInt(), anyInt()))
                .thenReturn(List.of(stop(1, 10), stop(2, null)));

        ScheduleDetailResponse response = service.start(1L, OWNER);

        assertThat(response.startedAt())
                .isEqualTo(java.time.OffsetDateTime.parse("2026-08-01T09:00:00Z"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("시작 — 남의 일정은 404 이고 시작 기록도 남지 않는다")
    void startOfOtherUserIsNotFound() {
        when(repository.findByScheduleIdAndUserId(1L, OTHER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(1L, OTHER))
                .isInstanceOf(SavedScheduleNotFoundException.class);
        verify(repository, never()).save(any());
    }
}

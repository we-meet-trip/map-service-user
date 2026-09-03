package map.service.user.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import map.service.user.schedule.ScheduleEntity;
import map.service.user.schedule.ScheduleRepository;
import map.service.user.trip.HubWeatherClient;
import map.service.user.trip.dto.HubWeatherDaily;
import map.service.user.trip.dto.HubWeatherResponse;
import map.service.user.weather.dto.WeatherAlert;
import map.service.user.weather.dto.WeatherSnapshotItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ScheduleWeatherServiceTest — 일정별 날씨 감시 동작 시험
 *
 * 저장 시점 예보를 기준선으로 굳히는 일과, 나중에 다시 받은 예보와 견줘
 * 알림을 남기는 일을 확인한다. hub 조회는 mock 이지만 판정·저장 경로는
 * 실제 코드가 돈다.
 */
@DisplayName("ScheduleWeatherService 일정 날씨 감시")
class ScheduleWeatherServiceTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 1);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ObjectMapper mapper =
            new ObjectMapper().registerModule(new JavaTimeModule());
    private final HubWeatherClient hub = mock(HubWeatherClient.class);
    private final ScheduleRepository repository = mock(ScheduleRepository.class);
    private final Clock clock =
            Clock.fixed(Instant.parse("2026-08-30T01:00:00Z"), KST);

    private ScheduleWeatherService service() {
        return new ScheduleWeatherService(hub, repository, mapper, clock);
    }

    private static HubWeatherResponse hubDay(int pop, String sky) {
        return new HubWeatherResponse(
                "서울특별시", "중구",
                List.of(new HubWeatherDaily(D1, 20, 28, pop, sky, "short")),
                List.of());
    }

    private static ScheduleEntity schedule() {
        ScheduleEntity entity = new ScheduleEntity(
                7L, java.util.UUID.randomUUID(), "가을 나들이",
                D1, D1, null, "walk", 10, 18);
        entity.setRegion("서울특별시", "중구");
        return entity;
    }

    @Test
    @DisplayName("저장 시점 예보를 기준선으로 굳힌다")
    void buildsBaselineFromHub() {
        when(hub.fetchWeather(eq("서울특별시"), eq("중구"), any(), any()))
                .thenReturn(hubDay(20, "맑음"));

        List<WeatherSnapshotItem> baseline =
                service().buildBaseline("서울특별시", "중구", D1, D1);

        assertThat(baseline).hasSize(1);
        assertThat(baseline.get(0).date()).isEqualTo(D1);
        assertThat(baseline.get(0).pop()).isEqualTo(20);
        assertThat(baseline.get(0).sky()).isEqualTo("sunny");
    }

    @Test
    @DisplayName("hub 가 날씨를 주지 못하면 기준선은 비어 있다")
    void baselineEmptyWhenHubHasNothing() {
        when(hub.fetchWeather(any(), any(), any(), any()))
                .thenReturn(new HubWeatherResponse(
                        "서울특별시", "중구", List.of(), List.of()));

        assertThat(service().buildBaseline("서울특별시", "중구", D1, D1))
                .isEmpty();
    }

    @Test
    @DisplayName("비 예보가 새로 생기면 알림을 남기고 저장한다")
    void recordsAlertWhenRainAppears() {
        ScheduleEntity entity = schedule();
        entity.setWeatherBaseline(mapper.valueToTree(
                List.of(new WeatherSnapshotItem(D1, 20, "sunny"))));
        when(hub.fetchWeather(any(), any(), any(), any()))
                .thenReturn(hubDay(80, "비"));

        boolean changed = service().check(entity);

        assertThat(changed).isTrue();
        WeatherAlert alert = service().readAlert(entity).orElseThrow();
        assertThat(alert.kind()).isEqualTo(WeatherAlert.RAIN_APPEARED);
        assertThat(alert.date()).isEqualTo(D1);
        assertThat(alert.detectedAt()).isNotNull();
        assertThat(entity.getWeatherCheckedAt()).isNotNull();
        verify(repository).save(entity);
    }

    @Test
    @DisplayName("변화가 없으면 확인 시각만 갱신한다")
    void onlyStampsCheckedAtWhenUnchanged() {
        ScheduleEntity entity = schedule();
        entity.setWeatherBaseline(mapper.valueToTree(
                List.of(new WeatherSnapshotItem(D1, 20, "sunny"))));
        when(hub.fetchWeather(any(), any(), any(), any()))
                .thenReturn(hubDay(30, "구름많음"));

        assertThat(service().check(entity)).isFalse();
        assertThat(service().readAlert(entity)).isEmpty();
        assertThat(entity.getWeatherCheckedAt()).isNotNull();
        verify(repository).save(entity);
    }

    @Test
    @DisplayName("지역을 모르는 일정은 견줄 수 없어 건너뛴다")
    void skipsScheduleWithoutRegion() {
        ScheduleEntity entity = new ScheduleEntity(
                7L, java.util.UUID.randomUUID(), "옛 일정",
                D1, D1, null, "walk", 10, 18);
        entity.setWeatherBaseline(mapper.valueToTree(
                List.of(new WeatherSnapshotItem(D1, 20, "sunny"))));

        assertThat(service().check(entity)).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("기준선이 없는 일정은 견줄 대상이 없어 건너뛴다")
    void skipsScheduleWithoutBaseline() {
        assertThat(service().check(schedule())).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("같은 변화를 이미 알렸으면 다시 저장하지 않는다")
    void doesNotRewriteSameAlert() {
        ScheduleEntity entity = schedule();
        entity.setWeatherBaseline(mapper.valueToTree(
                List.of(new WeatherSnapshotItem(D1, 20, "sunny"))));
        when(hub.fetchWeather(any(), any(), any(), any()))
                .thenReturn(hubDay(80, "비"));

        ScheduleWeatherService service = service();
        assertThat(service.check(entity)).isTrue();
        assertThat(service.check(entity)).isFalse();
    }

    @Test
    @DisplayName("변화를 처리했으면 기준선을 지금 예보로 옮기고 알림을 지운다")
    void acceptCurrentForecastMovesBaseline() {
        ScheduleEntity entity = schedule();
        entity.setWeatherBaseline(mapper.valueToTree(
                List.of(new WeatherSnapshotItem(D1, 20, "sunny"))));
        entity.setWeatherAlert(mapper.valueToTree(new WeatherAlert(
                WeatherAlert.RAIN_APPEARED, D1, 20, 80, "sunny", "rainy", null)));
        when(hub.fetchWeather(any(), any(), any(), any()))
                .thenReturn(hubDay(80, "비"));

        service().acceptCurrentForecast(entity);

        // 기준선이 지금 예보로 옮겨져야 같은 변화가 다시 알림으로 뜨지 않는다.
        assertThat(entity.getWeatherBaseline().get(0).get("pop").asInt())
                .isEqualTo(80);
        assertThat(entity.getWeatherAlert()).isNull();
        verify(repository).save(entity);
    }

    @Test
    @DisplayName("지금 예보를 못 받으면 기준선은 그대로 두고 알림만 지운다")
    void acceptCurrentForecastKeepsBaselineWhenHubSilent() {
        ScheduleEntity entity = schedule();
        entity.setWeatherBaseline(mapper.valueToTree(
                List.of(new WeatherSnapshotItem(D1, 20, "sunny"))));
        entity.setWeatherAlert(mapper.valueToTree(new WeatherAlert(
                WeatherAlert.RAIN_APPEARED, D1, 20, 80, "sunny", "rainy", null)));
        when(hub.fetchWeather(any(), any(), any(), any()))
                .thenReturn(new HubWeatherResponse(
                        "서울특별시", "중구", List.of(), List.of()));

        service().acceptCurrentForecast(entity);

        // 기준선을 비우면 그 일정이 감시 대상에서 영영 빠진다.
        assertThat(entity.getWeatherBaseline().get(0).get("pop").asInt())
                .isEqualTo(20);
        assertThat(entity.getWeatherAlert()).isNull();
    }

    @Test
    @DisplayName("지역을 모르는 일정도 알림은 지운다")
    void acceptCurrentForecastClearsAlertWithoutRegion() {
        ScheduleEntity entity = new ScheduleEntity(
                7L, java.util.UUID.randomUUID(), "옛 일정",
                D1, D1, null, "walk", 10, 18);
        entity.setWeatherAlert(mapper.valueToTree(new WeatherAlert(
                WeatherAlert.RAIN_APPEARED, D1, 20, 80, "sunny", "rainy", null)));

        service().acceptCurrentForecast(entity);

        assertThat(entity.getWeatherAlert()).isNull();
        verify(hub, never()).fetchWeather(any(), any(), any(), any());
    }
}

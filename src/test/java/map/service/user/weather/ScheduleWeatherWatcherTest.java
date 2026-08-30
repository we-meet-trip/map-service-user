package map.service.user.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import map.service.user.schedule.ScheduleEntity;
import map.service.user.schedule.ScheduleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ScheduleWeatherWatcherTest — 주기 감지 순회 시험
 *
 * 지나간 일정을 건드리지 않는 것과, 한 일정에서 터진 예외가 나머지 순회를
 * 끊지 않는 것을 확인한다. 판정 자체는 ScheduleWeatherService 의 몫이라
 * 여기서는 순회와 오류 격리만 본다.
 */
@DisplayName("ScheduleWeatherWatcher 주기 감지")
class ScheduleWeatherWatcherTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final Clock clock =
            Clock.fixed(Instant.parse("2026-08-30T01:00:00Z"), KST);

    private final ScheduleRepository repository = mock(ScheduleRepository.class);
    private final ScheduleWeatherService weatherService =
            mock(ScheduleWeatherService.class);

    private static ScheduleEntity schedule(LocalDate end) {
        return new ScheduleEntity(
                1L, UUID.randomUUID(), "여행", end, end, null, "walk", 10, 18);
    }

    @Test
    @DisplayName("오늘 이후로 남은 일정만 견준다")
    void checksOnlyUpcomingSchedules() {
        ScheduleEntity upcoming = schedule(LocalDate.of(2026, 9, 2));
        when(repository.findWatchTargets(LocalDate.of(2026, 8, 30)))
                .thenReturn(List.of(upcoming));

        int checked = new ScheduleWeatherWatcher(
                repository, weatherService, clock).scanUpcoming();

        assertThat(checked).isEqualTo(1);
        verify(weatherService).check(upcoming);
    }

    @Test
    @DisplayName("한 일정이 터져도 나머지는 계속 견준다")
    void keepsGoingWhenOneScheduleFails() {
        ScheduleEntity broken = schedule(LocalDate.of(2026, 9, 1));
        ScheduleEntity healthy = schedule(LocalDate.of(2026, 9, 2));
        when(repository.findWatchTargets(any()))
                .thenReturn(List.of(broken, healthy));
        doThrow(new IllegalStateException("hub down"))
                .when(weatherService).check(broken);

        int checked = new ScheduleWeatherWatcher(
                repository, weatherService, clock).scanUpcoming();

        assertThat(checked).isEqualTo(1);
        verify(weatherService).check(healthy);
    }
}

package map.service.user.weather;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import map.service.user.schedule.ScheduleEntity;
import map.service.user.schedule.ScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ScheduleWeatherWatcher — 남은 일정의 날씨를 주기적으로 다시 본다
 *
 * 예보는 하루에 여러 번 새로 나온다. 사용자가 앱을 열 때만 견주면 "출발 전날
 * 밤에 비 예보로 바뀐 것"을 아침에야 알게 되므로, 서버가 주기적으로 확인해
 * 알림을 미리 걸어 둔다.
 *
 * 한 일정에서 터진 예외가 순회를 끊지 않는다. hub 가 특정 지역에 대해서만
 * 실패하는 일이 흔한데, 그 하나 때문에 나머지 일정이 통째로 감시에서 빠지면
 * 안 된다.
 *
 * 주기와 사용 여부는 설정으로 바꾼다:
 * - weather-watch.enabled (기본 true)
 * - weather-watch.interval-ms (기본 30분)
 * 발급처 호출량이 걱정되면 주기를 늘리고, 시연에서는 줄여 쓴다.
 */
@Component
@ConditionalOnProperty(
        prefix = "weather-watch", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ScheduleWeatherWatcher {

    private static final Logger log =
            LoggerFactory.getLogger(ScheduleWeatherWatcher.class);

    private final ScheduleRepository repository;
    private final ScheduleWeatherService weatherService;
    private final Clock clock;

    @Autowired
    public ScheduleWeatherWatcher(
            ScheduleRepository repository,
            ScheduleWeatherService weatherService
    ) {
        this(repository, weatherService,
                Clock.system(java.time.ZoneId.of("Asia/Seoul")));
    }

    /** 오늘 날짜를 고정해 시험하기 위한 생성자. */
    ScheduleWeatherWatcher(
            ScheduleRepository repository,
            ScheduleWeatherService weatherService,
            Clock clock
    ) {
        this.repository = repository;
        this.weatherService = weatherService;
        this.clock = clock;
    }

    /** 주기 실행 진입점. 반환값이 없어야 하므로 순회 결과는 로그로만 남긴다. */
    @Scheduled(
            fixedDelayString = "${weather-watch.interval-ms:1800000}",
            initialDelayString = "${weather-watch.initial-delay-ms:60000}")
    public void scanOnSchedule() {
        scanUpcoming();
    }

    /**
     * 아직 끝나지 않은 일정을 모두 견준다.
     *
     * 실패한 일정은 세지 않는다 — "견줬다"고 세어 두면 hub 가 죽어 있는 동안
     * 아무것도 못 봤는데 다 본 것처럼 기록이 남는다.
     *
     * @return 이번 순회에서 실제로 견줘 본 일정 수
     */
    public int scanUpcoming() {
        LocalDate today = LocalDate.now(clock);
        List<ScheduleEntity> targets = repository.findWatchTargets(today);
        int checked = 0;
        int changed = 0;
        for (ScheduleEntity schedule : targets) {
            try {
                if (weatherService.check(schedule)) {
                    changed++;
                }
                checked++;
            } catch (RuntimeException e) {
                log.warn("weather watch failed scheduleId={} reason={}",
                        schedule.getScheduleId(), e.getMessage());
            }
        }
        if (changed > 0) {
            log.info("weather watch raised alerts checked={} changed={}",
                    checked, changed);
        }
        return checked;
    }
}

package map.service.user.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import map.service.user.schedule.ScheduleEntity;
import map.service.user.schedule.ScheduleRepository;
import map.service.user.trip.HubWeatherClient;
import map.service.user.trip.TripMapping;
import map.service.user.trip.dto.HubWeatherDaily;
import map.service.user.trip.dto.HubWeatherResponse;
import map.service.user.weather.dto.WeatherAlert;
import map.service.user.weather.dto.WeatherSnapshotItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * ScheduleWeatherService — 저장된 일정의 날씨를 지켜보는 일
 *
 * 하는 일은 둘이다. 일정을 저장할 때 그 시점 예보를 기준선으로 굳히고,
 * 나중에 예보를 다시 받아 기준선과 견줘 알림을 남긴다. 견주는 규칙 자체는
 * WeatherChangeRule 에 있고 여기서는 조회·저장만 맡는다.
 *
 * 날씨 조회 실패는 예외로 올리지 않는다(hub 원칙 그대로). 기준선을 못 굳히면
 * 그 일정은 감시 대상에서 조용히 빠질 뿐, 일정 저장 자체는 성공해야 한다.
 *
 * clock: 감지 시각의 출처. 주입으로 받아 시험에서 시각을 고정한다.
 */
@Service
public class ScheduleWeatherService {

    private static final Logger log =
            LoggerFactory.getLogger(ScheduleWeatherService.class);

    private final HubWeatherClient hubWeatherClient;
    private final ScheduleRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public ScheduleWeatherService(
            HubWeatherClient hubWeatherClient,
            ScheduleRepository repository,
            ObjectMapper objectMapper
    ) {
        this(hubWeatherClient, repository, objectMapper,
                Clock.system(java.time.ZoneId.of("Asia/Seoul")));
    }

    /** 시각을 고정해 시험하기 위한 생성자. 운영 경로는 위 생성자를 쓴다. */
    ScheduleWeatherService(
            HubWeatherClient hubWeatherClient,
            ScheduleRepository repository,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.hubWeatherClient = hubWeatherClient;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 지역·구간의 예보를 받아 날짜별 요약으로 접는다.
     *
     * 강수확률과 하늘 상태 중 하나라도 없는 날은 버린다 — 반쪽 값을 기준선에
     * 넣으면 나중에 "달라졌다"인지 "원래 몰랐다"인지 구별할 수 없다.
     * 지역이 없거나 hub 가 답하지 못하면 빈 목록.
     */
    public List<WeatherSnapshotItem> buildBaseline(
            String province, String city, LocalDate start, LocalDate end
    ) {
        if (province == null || city == null || start == null || end == null) {
            return List.of();
        }
        HubWeatherResponse hub =
                hubWeatherClient.fetchWeather(province, city, start, end);
        return toSnapshot(hub);
    }

    /** 기준선 목록을 그대로 저장할 수 있는 JSON 으로 바꾼다. 비면 null. */
    public JsonNode toJson(List<WeatherSnapshotItem> baseline) {
        if (baseline == null || baseline.isEmpty()) {
            return null;
        }
        return objectMapper.valueToTree(baseline);
    }

    /**
     * 일정 하나를 지금 예보와 견준다.
     *
     * 지역이나 기준선이 없으면 견줄 수 없어 아무것도 하지 않는다(저장도 하지
     * 않는다 — 감시 대상이 아닌 행에 확인 시각만 남기면 "확인했는데 멀쩡함"
     * 처럼 보인다).
     *
     * 이미 같은 알림이 걸려 있으면 다시 쓰지 않는다. 30분마다 같은 알림을
     * 새로 쓰면 detected_at 이 계속 밀려 사용자가 언제 바뀐 건지 알 수 없다.
     *
     * @return 이번 호출로 새 알림을 남겼으면 true
     */
    public boolean check(ScheduleEntity schedule) {
        List<WeatherSnapshotItem> baseline = readBaseline(schedule);
        if (schedule.getProvince() == null || schedule.getCity() == null
                || baseline.isEmpty()) {
            return false;
        }

        List<WeatherSnapshotItem> current = buildBaseline(
                schedule.getProvince(), schedule.getCity(),
                schedule.getDateStart(), schedule.getDateEnd());

        Optional<WeatherAlert> found =
                WeatherChangeRule.detect(baseline, current);
        boolean recorded = false;
        if (found.isPresent() && !isSameAsCurrentAlert(schedule, found.get())) {
            WeatherAlert alert = withDetectedAt(found.get());
            schedule.setWeatherAlert(objectMapper.valueToTree(alert));
            recorded = true;
        }
        schedule.setWeatherCheckedAt(OffsetDateTime.now(clock));
        repository.save(schedule);
        return recorded;
    }

    /** 일정에 걸린 알림을 읽는다. 없거나 형태가 깨졌으면 빈 값. */
    public Optional<WeatherAlert> readAlert(ScheduleEntity schedule) {
        JsonNode node = schedule.getWeatherAlert();
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.treeToValue(node, WeatherAlert.class));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("weather_alert parse failed scheduleId={} reason={}",
                    schedule.getScheduleId(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 사용자가 그 변화를 처리했다고 보고, 기준선을 지금 예보로 옮기며 알림을 지운다.
     *
     * 재추천을 눌렀든("다시 짜 줘") 무시했든("이대로 갈래") 사용자는 이미 그
     * 변화를 알고 행동한 것이다. 그러니 같은 변화로 다시 알릴 이유가 없다.
     * 알림만 지우면 다음 순회가 같은 차이를 또 발견해 30분마다 배너가 되살아난다
     * — 실제로 그렇게 동작하는 것을 통합 검증에서 확인했다.
     *
     * 기준선을 옮겨 두면 이후 예보가 <b>또</b> 달라졌을 때만(비가 그치거나 다른
     * 날이 나빠졌을 때) 새 기준선 대비로 알린다.
     *
     * 지금 예보를 받지 못하면 기준선은 건드리지 않고 알림만 지운다. 기준선을
     * 비우면 그 일정이 감시 대상에서 영영 빠진다(감시 조건이 기준선 보유다).
     * 그 경우 같은 알림이 다시 뜰 수 있지만, 영영 안 뜨는 쪽보다 낫다.
     */
    public void acceptCurrentForecast(ScheduleEntity schedule) {
        if (schedule.getProvince() != null && schedule.getCity() != null) {
            JsonNode moved = toJson(buildBaseline(
                    schedule.getProvince(), schedule.getCity(),
                    schedule.getDateStart(), schedule.getDateEnd()));
            if (moved != null) {
                schedule.setWeatherBaseline(moved);
            }
        }
        schedule.setWeatherAlert(null);
        repository.save(schedule);
    }

    private List<WeatherSnapshotItem> readBaseline(ScheduleEntity schedule) {
        JsonNode node = schedule.getWeatherBaseline();
        if (node == null || node.isNull()) {
            return List.of();
        }
        try {
            return objectMapper.convertValue(
                    node, new TypeReference<List<WeatherSnapshotItem>>() {});
        } catch (RuntimeException e) {
            log.warn("weather_baseline parse failed scheduleId={} reason={}",
                    schedule.getScheduleId(), e.getMessage());
            return List.of();
        }
    }

    private boolean isSameAsCurrentAlert(
            ScheduleEntity schedule, WeatherAlert candidate
    ) {
        return readAlert(schedule)
                .filter(now -> now.kind().equals(candidate.kind())
                        && now.date().equals(candidate.date()))
                .isPresent();
    }

    private WeatherAlert withDetectedAt(WeatherAlert alert) {
        return new WeatherAlert(
                alert.kind(), alert.date(),
                alert.popBefore(), alert.popAfter(),
                alert.skyBefore(), alert.skyAfter(),
                OffsetDateTime.now(clock));
    }

    private static List<WeatherSnapshotItem> toSnapshot(HubWeatherResponse hub) {
        List<WeatherSnapshotItem> out = new ArrayList<>();
        if (hub == null || hub.daily() == null) {
            return out;
        }
        for (HubWeatherDaily d : hub.daily()) {
            if (d == null || d.date() == null
                    || d.precipitationProb() == null
                    || d.skyCondition() == null) {
                continue;
            }
            out.add(new WeatherSnapshotItem(
                    d.date(),
                    d.precipitationProb(),
                    TripMapping.skyToCondition(d.skyCondition())));
        }
        return out;
    }
}

package map.service.user.weather;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import map.service.user.weather.dto.WeatherAlert;
import map.service.user.weather.dto.WeatherSnapshotItem;

/**
 * WeatherChangeRule — 재계획할 만한 날씨 변화인지 가르는 규칙
 *
 * 저장 시점 예보(기준선)와 지금 예보를 날짜별로 견준다. 예보는 발표 때마다
 * 조금씩 흔들리므로 차이를 전부 알리면 배너가 소음이 된다. 그래서 코스 구성이
 * 실제로 달라지는 지점 — 비·눈 여부가 뒤집히는 날만 잡는다(판단 기준은
 * WeatherSnapshotItem.wet).
 *
 * 나빠진 쪽(rain_appeared)을 좋아진 쪽(rain_cleared)보다 먼저 알린다. 야외
 * 일정을 비 맞으며 도는 쪽이 실내 일정을 맑은 날 도는 쪽보다 손해가 크다.
 *
 * 상태가 없는 순수 함수라 스케줄러·테스트 어디서든 같은 결과를 낸다.
 * detectedAt 은 여기서 채우지 않는다 — 시각은 호출부(감지 시점)의 몫이다.
 */
public final class WeatherChangeRule {

    private WeatherChangeRule() {
    }

    /**
     * 기준선 대비 변화를 한 건 고른다. 알릴 만한 변화가 없으면 빈 값.
     *
     * baseline: 일정 저장 시점의 날짜별 예보.
     * current: 지금 다시 받은 날짜별 예보.
     *
     * 기준선에 없는 날짜는 견줄 대상이 없어 건너뛴다. 강수확률이 비어 있어도
     * 하늘 상태만으로 판단할 수 있으면 판단한다.
     */
    public static Optional<WeatherAlert> detect(
            List<WeatherSnapshotItem> baseline,
            List<WeatherSnapshotItem> current
    ) {
        if (baseline == null || current == null
                || baseline.isEmpty() || current.isEmpty()) {
            return Optional.empty();
        }

        Map<LocalDate, WeatherSnapshotItem> before = new HashMap<>();
        for (WeatherSnapshotItem item : baseline) {
            if (item != null && item.date() != null) {
                before.put(item.date(), item);
            }
        }

        WeatherAlert appeared = null;
        WeatherAlert cleared = null;

        for (WeatherSnapshotItem after : current) {
            if (after == null || after.date() == null) {
                continue;
            }
            WeatherSnapshotItem was = before.get(after.date());
            if (was == null || was.wet() == after.wet()) {
                continue;
            }
            if (after.wet()) {
                if (appeared == null || after.date().isBefore(appeared.date())) {
                    appeared = alert(WeatherAlert.RAIN_APPEARED, was, after);
                }
            } else if (cleared == null || after.date().isBefore(cleared.date())) {
                cleared = alert(WeatherAlert.RAIN_CLEARED, was, after);
            }
        }

        return Optional.ofNullable(appeared != null ? appeared : cleared);
    }

    private static WeatherAlert alert(
            String kind, WeatherSnapshotItem was, WeatherSnapshotItem now
    ) {
        return new WeatherAlert(
                kind, now.date(),
                was.pop(), now.pop(),
                was.sky(), now.sky(),
                null);
    }
}

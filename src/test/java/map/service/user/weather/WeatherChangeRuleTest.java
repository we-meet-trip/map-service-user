package map.service.user.weather;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import map.service.user.weather.dto.WeatherAlert;
import map.service.user.weather.dto.WeatherSnapshotItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * WeatherChangeRuleTest — 날씨 변화 판정 규칙 단위 테스트
 *
 * 일정을 저장하던 시점의 예보(기준선)와 지금 예보를 견줘, 코스를 다시 짜야
 * 할 만한 변화인지 가른다. 예보는 늘 조금씩 흔들리므로 "달라졌다"를 전부
 * 알리면 알림이 소음이 된다. 그래서 실내/야외 판단이 뒤집히는 지점 —
 * 강수확률 50% 경계를 넘나드는 경우와 비·눈으로 하늘 상태가 바뀌는 경우만
 * 잡는다.
 */
@DisplayName("WeatherChangeRule 날씨 변화 판정")
class WeatherChangeRuleTest {

    private static final LocalDate D1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 2);

    private static WeatherSnapshotItem item(LocalDate date, int pop, String sky) {
        return new WeatherSnapshotItem(date, pop, sky);
    }

    @Test
    @DisplayName("강수확률이 임계를 넘어서면 비 예보 발생으로 본다")
    void detectsRainAppeared() {
        Optional<WeatherAlert> alert = WeatherChangeRule.detect(
                List.of(item(D1, 20, "sunny")),
                List.of(item(D1, 70, "rainy")));

        assertThat(alert).isPresent();
        assertThat(alert.get().kind()).isEqualTo("rain_appeared");
        assertThat(alert.get().date()).isEqualTo(D1);
        assertThat(alert.get().popBefore()).isEqualTo(20);
        assertThat(alert.get().popAfter()).isEqualTo(70);
    }

    @Test
    @DisplayName("강수확률이 임계 아래로 내려가면 비 예보 해제로 본다")
    void detectsRainCleared() {
        Optional<WeatherAlert> alert = WeatherChangeRule.detect(
                List.of(item(D1, 80, "rainy")),
                List.of(item(D1, 10, "sunny")));

        assertThat(alert).isPresent();
        assertThat(alert.get().kind()).isEqualTo("rain_cleared");
    }

    @Test
    @DisplayName("임계를 넘지 않는 흔들림은 알리지 않는다")
    void ignoresNoiseBelowThreshold() {
        assertThat(WeatherChangeRule.detect(
                List.of(item(D1, 20, "sunny")),
                List.of(item(D1, 40, "cloudy")))).isEmpty();
    }

    @Test
    @DisplayName("강수확률이 그대로여도 하늘 상태가 비·눈으로 바뀌면 알린다")
    void detectsSkyTurningWet() {
        Optional<WeatherAlert> alert = WeatherChangeRule.detect(
                List.of(item(D1, 30, "cloudy")),
                List.of(item(D1, 30, "snowy")));

        assertThat(alert).isPresent();
        assertThat(alert.get().kind()).isEqualTo("rain_appeared");
    }

    @Test
    @DisplayName("여러 날이 바뀌면 나빠진 날을 먼저 알린다")
    void prefersWorseningDay() {
        Optional<WeatherAlert> alert = WeatherChangeRule.detect(
                List.of(item(D1, 80, "rainy"), item(D2, 10, "sunny")),
                List.of(item(D1, 10, "sunny"), item(D2, 90, "rainy")));

        assertThat(alert).isPresent();
        assertThat(alert.get().kind()).isEqualTo("rain_appeared");
        assertThat(alert.get().date()).isEqualTo(D2);
    }

    @Test
    @DisplayName("기준선에 없던 날짜는 견줄 대상이 없어 넘어간다")
    void skipsDatesMissingFromBaseline() {
        assertThat(WeatherChangeRule.detect(
                List.of(item(D1, 20, "sunny")),
                List.of(item(D2, 90, "rainy")))).isEmpty();
    }

    @Test
    @DisplayName("기준선이나 현재 예보가 비면 판정하지 않는다")
    void skipsWhenEitherSideEmpty() {
        assertThat(WeatherChangeRule.detect(
                List.of(), List.of(item(D1, 90, "rainy")))).isEmpty();
        assertThat(WeatherChangeRule.detect(
                List.of(item(D1, 90, "rainy")), List.of())).isEmpty();
    }
}

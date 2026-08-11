package map.service.user.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import map.service.user.weather.dto.HubWeatherNowResponse;
import map.service.user.weather.dto.WeatherHomeResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * WeatherHomeServiceTest — 홈 카드 조립 규칙 단위 테스트
 *
 * hub 가 내려준 묶음을 화면이 읽는 평평한 형태로 접는 과정과, 값이 없는
 * 항목을 어떻게 다루는지를 검증한다.
 */
@DisplayName("WeatherHomeService 홈 카드 조립")
class WeatherHomeServiceTest {

    private static HubWeatherNowResponse.Observation obs(double temp) {
        return new HubWeatherNowResponse.Observation(
                temp, 0, "20260801", "1000", "2026-08-01T10:00:00+09:00");
    }

    private static WeatherHomeResponse call(HubWeatherNowResponse hub) {
        HubWeatherNowClient client = mock(HubWeatherNowClient.class);
        when(client.fetchNow(anyDouble(), anyDouble())).thenReturn(hub);
        return new WeatherHomeService(client).fetchHome(37.5, 127.0);
    }

    @Test
    @DisplayName("실황·예보·대기를 한 형태로 합쳐 내려준다")
    void mergesAllSources() {
        WeatherHomeResponse out = call(new HubWeatherNowResponse(
                60, 127, "서울특별시", "중구",
                obs(27.3),
                new HubWeatherNowResponse.Yesterday(29.1, 10),
                new HubWeatherNowResponse.Today(31, 24, 30, "맑음"),
                new HubWeatherNowResponse.Air(
                        21, 11, "좋음", "좋음", "중구",
                        "2026-08-01T10:00:00+09:00")));

        assertThat(out.temp()).isEqualTo(27.3);
        assertThat(out.sky()).isEqualTo("맑음");
        assertThat(out.tempMax()).isEqualTo(31);
        assertThat(out.tempMin()).isEqualTo(24);
        assertThat(out.pop()).isEqualTo(30);
        assertThat(out.pm10Grade()).isEqualTo("좋음");
        assertThat(out.attribution()).isEqualTo("기상청, 한국환경공단 제공");
    }

    @Test
    @DisplayName("어제 대비 기온 차를 소수 첫째 자리까지만 계산한다")
    void roundsYesterdayDiff() {
        WeatherHomeResponse out = call(new HubWeatherNowResponse(
                60, 127, "서울특별시", "중구",
                obs(27.3),
                new HubWeatherNowResponse.Yesterday(29.1, 10),
                null, null));

        assertThat(out.yesterdayDiff()).isEqualTo(-1.8);
    }

    @Test
    @DisplayName("어제 기록이 없으면 비교 값을 비운다")
    void omitsYesterdayDiffWithoutRecord() {
        WeatherHomeResponse out = call(new HubWeatherNowResponse(
                60, 127, "서울특별시", "중구", obs(27.3), null, null, null));

        assertThat(out.yesterdayDiff()).isNull();
    }

    @Test
    @DisplayName("예보·대기가 없어도 지금 기온만으로 응답한다")
    void survivesWithoutForecastAndAir() {
        WeatherHomeResponse out = call(new HubWeatherNowResponse(
                60, 127, null, null, obs(27.3), null, null, null));

        assertThat(out.temp()).isEqualTo(27.3);
        assertThat(out.sky()).isNull();
        assertThat(out.tempMax()).isNull();
        assertThat(out.pm10()).isNull();
    }

    @Test
    @DisplayName("hub 가 본문을 안 주면 오류로 알린다")
    void failsWithoutBody() {
        HubWeatherNowClient client = mock(HubWeatherNowClient.class);
        when(client.fetchNow(anyDouble(), anyDouble())).thenReturn(null);

        assertThatThrownBy(
                () -> new WeatherHomeService(client).fetchHome(37.5, 127.0))
                .isInstanceOf(WeatherUnavailableException.class);
    }

    @Test
    @DisplayName("실황이 비어 와도 남은 항목으로 응답한다")
    void survivesWithoutObservation() {
        // 서버가 미리 받아 둔 실황이 낡아 비워 보낸 상황. 기온만 없을 뿐
        // 예보와 미세먼지는 살아 있는데, 여기서 실패시키면 그것까지 함께
        // 사라져 카드가 통째로 비어 버린다.
        WeatherHomeResponse out = call(new HubWeatherNowResponse(
                60, 127, "서울특별시", "중구",
                null,
                new HubWeatherNowResponse.Yesterday(29.1, 10),
                new HubWeatherNowResponse.Today(31, 24, 30, "맑음"),
                new HubWeatherNowResponse.Air(
                        21, 11, "좋음", "좋음", "중구",
                        "2026-08-01T10:00:00+09:00")));

        assertThat(out.temp()).isNull();
        assertThat(out.pty()).isNull();
        assertThat(out.observedAt()).isNull();
        // 기준 기온이 없으면 비교도 뜻이 없다.
        assertThat(out.yesterdayDiff()).isNull();
        // 남은 것은 그대로 나간다.
        assertThat(out.sky()).isEqualTo("맑음");
        assertThat(out.pm10()).isEqualTo(21);
        assertThat(out.airObservedAt()).isEqualTo("2026-08-01T10:00:00+09:00");
    }

    @Test
    @DisplayName("관측 시각을 그대로 실어 보낸다")
    void passesObservedAt() {
        // 미리 받아 둔 값이라 지금 시각과 다를 수 있다. 화면이 "몇 시 기준"
        // 인지 밝히려면 이 값이 있어야 한다.
        WeatherHomeResponse out = call(new HubWeatherNowResponse(
                60, 127, "서울특별시", "중구", obs(27.3), null, null, null));

        assertThat(out.observedAt()).isEqualTo("2026-08-01T10:00:00+09:00");
    }

    @Test
    @DisplayName("hub 호출이 실패하면 오류로 감싼다")
    void wrapsUpstreamFailure() {
        HubWeatherNowClient client = mock(HubWeatherNowClient.class);
        when(client.fetchNow(anyDouble(), anyDouble()))
                .thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(
                () -> new WeatherHomeService(client).fetchHome(37.5, 127.0))
                .isInstanceOf(WeatherUnavailableException.class);
    }
}

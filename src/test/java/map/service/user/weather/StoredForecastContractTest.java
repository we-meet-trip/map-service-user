package map.service.user.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import map.service.user.trip.HubWeatherClient;
import map.service.user.trip.TripMapping;
import map.service.user.trip.dto.HubWeatherDaily;
import map.service.user.trip.dto.HubWeatherResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class StoredForecastContractTest {
    private final LocalDate start = LocalDate.of(2026, 9, 7);

    @Test void partialDailyForecastIsPreservedWithoutInventedWeather() {
        var hub = new HubWeatherResponse("서울특별시", "종로구", List.of(
            new HubWeatherDaily(start, null, 29, null, null, "mid_temp")), List.of());
        var item = TripMapping.toWeatherForecast(hub).get(0);
        assertThat(item.tempHigh()).isEqualTo(29);
        assertThat(item.tempLow()).isNull();
        assertThat(item.condition()).isNull();
        assertThat(item.precipitationProbability()).isNull();
    }

    @Test void regionAndCalendarRangeArePassedThroughToStoredForecastClient() {
        var client = mock(HubWeatherClient.class);
        var expected = new HubWeatherResponse("부산광역시", "해운대구", List.of(), List.of(start));
        when(client.fetchWeather("부산광역시", "해운대구", start, start.plusDays(8))).thenReturn(expected);
        var response = new WeatherForecastController(client).forecast("부산광역시", "해운대구", start, start.plusDays(8));
        assertThat(response).isSameAs(expected);
        verify(client).fetchWeather("부산광역시", "해운대구", start, start.plusDays(8));
    }

    @Test void invalidRangesNeverCallHub() {
        var client = mock(HubWeatherClient.class);
        var controller = new WeatherForecastController(client);
        assertThatThrownBy(() -> controller.forecast("서울", "종로구", start, start.minusDays(1)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.forecast("서울", "종로구", start, start.plusDays(14)))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(client);
    }

    @Test void knownZeroAndKnownSkyRemainDistinctFromMissing() {
        assertThat(TripMapping.skyToCondition("맑음")).isEqualTo("sunny");
        assertThat(TripMapping.skyToCondition("구름많음")).isEqualTo("cloudy");
        assertThat(TripMapping.skyToCondition("알 수 없음")).isNull();
        assertThat(TripMapping.skyToCondition(null)).isNull();
    }
}

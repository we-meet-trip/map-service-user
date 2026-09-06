package map.service.user.weather;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import map.service.user.trip.HubWeatherClient;
import map.service.user.trip.dto.HubWeatherResponse;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Stored KMA forecasts for the itinerary region/date; never current observations. */
@RestController
@Validated
public class WeatherForecastController {
    private final HubWeatherClient client;

    public WeatherForecastController(HubWeatherClient client) {
        this.client = client;
    }

    @GetMapping("/api/v1/weather/forecast")
    public HubWeatherResponse forecast(
            @RequestParam @NotBlank @Size(max = 20) String province,
            @RequestParam @NotBlank @Size(max = 20) String city,
            @RequestParam("date_start") LocalDate start,
            @RequestParam("date_end") LocalDate end) {
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= 14) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date range must be 1..14 days");
        }
        return client.fetchWeather(province, city, start, end);
    }
}

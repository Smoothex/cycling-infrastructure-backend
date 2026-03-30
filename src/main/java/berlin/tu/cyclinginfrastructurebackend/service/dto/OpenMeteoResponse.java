package berlin.tu.cyclinginfrastructurebackend.service.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Maps the Open-Meteo Historical Weather API JSON response.
 * <a href="https://open-meteo.com/en/docs/historical-weather-api#api_documentation">API Documentation</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenMeteoResponse(
        @JsonProperty("hourly") HourlyData hourly
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HourlyData(
            @JsonProperty("time") List<String> time,
            @JsonProperty("temperature_2m") List<Double> temperature2m,
            @JsonProperty("precipitation") List<Double> precipitation,
            @JsonProperty("wind_speed_10m") List<Double> windSpeed10m,
            @JsonProperty("wind_direction_10m") List<Double> windDirection10m,
            @JsonProperty("weather_code") List<Integer> weatherCode
    ) {}
}

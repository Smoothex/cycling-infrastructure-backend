package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenMeteoBulkClientTest {

    private final OpenMeteoBulkClient client = new OpenMeteoBulkClient(
            RestClient.builder(), new OpenMeteoProperties());

    @Test
    void parsesSingleObjectUnixTimestampsAndNullValues() {
        String response = """
                {
                  "hourly": {
                    "time": [1704067200, 1704070800],
                    "temperature_2m": [1.5, null],
                    "precipitation": [0.0, 0.2],
                    "wind_speed_10m": [10.0, null],
                    "wind_direction_10m": [180.0, 200.0],
                    "weather_code": [3, null]
                  }
                }
                """;

        List<OpenMeteoHourlyRow> rows = client.parseResponse(
                response, List.of(new OpenMeteoLocation(525, 134)));

        assertThat(rows).containsExactly(
                new OpenMeteoHourlyRow(525, 134, 1_704_067_200_000L,
                        1.5, 0.0, 10.0, 180.0, 3),
                new OpenMeteoHourlyRow(525, 134, 1_704_070_800_000L,
                        null, 0.2, null, 200.0, null));
    }

    @Test
    void parsesMultipleLocationsInRequestOrder() {
        String response = """
                [
                  {"hourly": {
                    "time": [1704067200],
                    "temperature_2m": [1.0], "precipitation": [0.0],
                    "wind_speed_10m": [2.0], "wind_direction_10m": [3.0], "weather_code": [4]
                  }},
                  {"hourly": {
                    "time": [1704067200],
                    "temperature_2m": [5.0], "precipitation": [6.0],
                    "wind_speed_10m": [7.0], "wind_direction_10m": [8.0], "weather_code": [9]
                  }}
                ]
                """;
        List<OpenMeteoLocation> locations = List.of(
                new OpenMeteoLocation(520, 130),
                new OpenMeteoLocation(530, 140));

        List<OpenMeteoHourlyRow> rows = client.parseResponse(response, locations);

        assertThat(rows).extracting(OpenMeteoHourlyRow::latitudeTenths).containsExactly(520, 530);
        assertThat(rows).extracting(OpenMeteoHourlyRow::temperature2m).containsExactly(1.0, 5.0);
    }

    @Test
    void rejectsIncorrectResponseCount() {
        String response = """
                {"hourly": {
                  "time": [], "temperature_2m": [], "precipitation": [],
                  "wind_speed_10m": [], "wind_direction_10m": [], "weather_code": []
                }}
                """;

        assertThatThrownBy(() -> client.parseResponse(response, List.of(
                new OpenMeteoLocation(520, 130),
                new OpenMeteoLocation(530, 140))))
                .isInstanceOf(OpenMeteoResponseException.class)
                .hasMessageContaining("1 locations for 2 requested");
    }

    @Test
    void rejectsMisalignedHourlyArrays() {
        String response = """
                {"hourly": {
                  "time": [1704067200, 1704070800],
                  "temperature_2m": [1.0],
                  "precipitation": [0.0, 0.0],
                  "wind_speed_10m": [2.0, 2.0],
                  "wind_direction_10m": [3.0, 3.0],
                  "weather_code": [4, 4]
                }}
                """;

        assertThatThrownBy(() -> client.parseResponse(
                response, List.of(new OpenMeteoLocation(520, 130))))
                .isInstanceOf(OpenMeteoResponseException.class)
                .hasMessageContaining("temperature_2m")
                .hasMessageContaining("expected 2");
    }

    @Test
    void rejectsNonUnixTimeValues() {
        String response = """
                {"hourly": {
                  "time": ["2024-01-01T00:00"],
                  "temperature_2m": [1.0], "precipitation": [0.0],
                  "wind_speed_10m": [2.0], "wind_direction_10m": [3.0], "weather_code": [4]
                }}
                """;

        assertThatThrownBy(() -> client.parseResponse(
                response, List.of(new OpenMeteoLocation(520, 130))))
                .isInstanceOf(OpenMeteoResponseException.class)
                .hasMessageContaining("not a Unix timestamp");
    }

    @Test
    void classifiesRetryableStatuses() {
        assertThat(OpenMeteoBulkClient.isRetryableStatus(HttpStatus.TOO_MANY_REQUESTS)).isTrue();
        assertThat(OpenMeteoBulkClient.isRetryableStatus(HttpStatus.REQUEST_TIMEOUT)).isTrue();
        assertThat(OpenMeteoBulkClient.isRetryableStatus(HttpStatus.SERVICE_UNAVAILABLE)).isTrue();
        assertThat(OpenMeteoBulkClient.isRetryableStatus(HttpStatus.BAD_REQUEST)).isFalse();
    }

    @Test
    void calculatesRateLimitDeadlinesFromQuotaMessages() {
        Instant now = Instant.parse("2024-01-01T10:23:45Z");

        assertThat(OpenMeteoBulkClient.retryAt("Minutely API request limit exceeded", now))
                .isEqualTo(Instant.parse("2024-01-01T10:25:00Z"));
        assertThat(OpenMeteoBulkClient.retryAt("Hourly API request limit exceeded", now))
                .isEqualTo(Instant.parse("2024-01-01T11:02:00Z"));
        assertThat(OpenMeteoBulkClient.retryAt("Daily API request limit exceeded", now))
                .isEqualTo(Instant.parse("2024-01-02T00:05:00Z"));
        assertThat(OpenMeteoBulkClient.retryAt("unknown", now))
                .isEqualTo(Instant.parse("2024-01-01T10:38:45Z"));
    }
}

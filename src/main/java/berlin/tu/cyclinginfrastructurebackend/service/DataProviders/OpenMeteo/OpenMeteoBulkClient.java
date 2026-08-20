package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo;

import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.ApiRateLimitException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Bulk client for Open-Meteo's historical hourly archive API. */
@Component
public class OpenMeteoBulkClient {

    private static final String BASE_URL = "https://archive-api.open-meteo.com";
    static final String HOURLY_FIELDS =
            "temperature_2m,precipitation,wind_speed_10m,wind_direction_10m,weather_code";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenMeteoBulkClient(RestClient.Builder restClientBuilder,
                               OpenMeteoProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getRequestTimeout());
        this.restClient = restClientBuilder.clone()
                .baseUrl(BASE_URL)
                .requestFactory(requestFactory)
                .build();
    }

    public List<OpenMeteoHourlyRow> fetch(List<OpenMeteoLocation> locations,
                                          LocalDate startDate,
                                          LocalDate endDate) {
        validateRequest(locations, startDate, endDate);
        String latitudes = coordinateParameter(locations, true);
        String longitudes = coordinateParameter(locations, false);

        final String responseBody;
        try {
            responseBody = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/archive")
                            .queryParam("latitude", latitudes)
                            .queryParam("longitude", longitudes)
                            .queryParam("start_date", startDate)
                            .queryParam("end_date", endDate)
                            .queryParam("hourly", HOURLY_FIELDS)
                            .queryParam("timezone", "GMT")
                            .queryParam("timeformat", "unixtime")
                            .build())
                    .retrieve()
                    .body(String.class);
        } catch (ResourceAccessException exception) {
            throw new OpenMeteoRetryableException("Open-Meteo request timed out or failed", exception);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 429) {
                Instant retryAt = retryAt(exception.getResponseBodyAsString(), Instant.now());
                throw new ApiRateLimitException("Open-Meteo rate limit exceeded", retryAt, exception);
            }
            if (isRetryableStatus(exception.getStatusCode())) {
                throw new OpenMeteoRetryableException(
                        "Open-Meteo server error " + exception.getStatusCode(), exception);
            }
            throw new OpenMeteoResponseException(
                    "Open-Meteo rejected the request with " + exception.getStatusCode(), exception);
        }

        return parseResponse(responseBody, locations);
    }

    List<OpenMeteoHourlyRow> parseResponse(String responseBody, List<OpenMeteoLocation> locations) {
        if (responseBody == null || responseBody.isBlank()) {
            throw new OpenMeteoResponseException("Open-Meteo returned an empty response");
        }

        final JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            throw new OpenMeteoResponseException("Open-Meteo returned malformed JSON", exception);
        }

        List<JsonNode> locationResponses = new ArrayList<>();
        if (root.isObject()) {
            locationResponses.add(root);
        } else if (root.isArray()) {
            root.forEach(locationResponses::add);
        } else {
            throw new OpenMeteoResponseException("Open-Meteo response must be an object or array");
        }
        if (locationResponses.size() != locations.size()) {
            throw new OpenMeteoResponseException("Open-Meteo returned " + locationResponses.size()
                    + " locations for " + locations.size() + " requested locations");
        }

        List<OpenMeteoHourlyRow> rows = new ArrayList<>();
        for (int locationIndex = 0; locationIndex < locations.size(); locationIndex++) {
            JsonNode response = locationResponses.get(locationIndex);
            JsonNode hourly = response.get("hourly");
            if (hourly == null || !hourly.isObject()) {
                throw new OpenMeteoResponseException(
                        "Open-Meteo response " + locationIndex + " has no hourly object");
            }

            JsonNode times = requiredArray(hourly, "time", locationIndex);
            JsonNode temperatures = requiredArray(hourly, "temperature_2m", locationIndex);
            JsonNode precipitation = requiredArray(hourly, "precipitation", locationIndex);
            JsonNode windSpeeds = requiredArray(hourly, "wind_speed_10m", locationIndex);
            JsonNode windDirections = requiredArray(hourly, "wind_direction_10m", locationIndex);
            JsonNode weatherCodes = requiredArray(hourly, "weather_code", locationIndex);
            int expectedLength = times.size();
            requireLength(temperatures, expectedLength, "temperature_2m", locationIndex);
            requireLength(precipitation, expectedLength, "precipitation", locationIndex);
            requireLength(windSpeeds, expectedLength, "wind_speed_10m", locationIndex);
            requireLength(windDirections, expectedLength, "wind_direction_10m", locationIndex);
            requireLength(weatherCodes, expectedLength, "weather_code", locationIndex);

            OpenMeteoLocation location = locations.get(locationIndex);
            for (int hourIndex = 0; hourIndex < expectedLength; hourIndex++) {
                JsonNode time = times.get(hourIndex);
                if (!time.isIntegralNumber() || !time.canConvertToLong()) {
                    throw new OpenMeteoResponseException("Open-Meteo time at location "
                            + locationIndex + ", index " + hourIndex + " is not a Unix timestamp");
                }
                final long validFrom;
                try {
                    validFrom = Math.multiplyExact(time.longValue(), 1_000L);
                } catch (ArithmeticException exception) {
                    throw new OpenMeteoResponseException("Open-Meteo Unix timestamp overflows milliseconds", exception);
                }
                rows.add(new OpenMeteoHourlyRow(
                        location.latitudeTenths(),
                        location.longitudeTenths(),
                        validFrom,
                        nullableDouble(temperatures.get(hourIndex), "temperature_2m", locationIndex, hourIndex),
                        nullableDouble(precipitation.get(hourIndex), "precipitation", locationIndex, hourIndex),
                        nullableDouble(windSpeeds.get(hourIndex), "wind_speed_10m", locationIndex, hourIndex),
                        nullableDouble(windDirections.get(hourIndex), "wind_direction_10m", locationIndex, hourIndex),
                        nullableInteger(weatherCodes.get(hourIndex), "weather_code", locationIndex, hourIndex)
                ));
            }
        }
        return List.copyOf(rows);
    }

    static boolean isRetryableStatus(HttpStatusCode status) {
        return status.value() == 408 || status.value() == 429 || status.is5xxServerError();
    }

    static Instant retryAt(String responseBody, Instant now) {
        String body = responseBody == null ? "" : responseBody;
        if (body.contains("Minutely")) {
            return now.plusSeconds(75);
        }
        if (body.contains("Hourly")) {
            return now.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS)
                    .plusHours(1).toInstant().plus(Duration.ofMinutes(2));
        }
        if (body.contains("Daily")) {
            return now.atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS)
                    .plusDays(1).toInstant().plus(Duration.ofMinutes(5));
        }
        return now.plus(Duration.ofMinutes(15));
    }

    private void validateRequest(List<OpenMeteoLocation> locations,
                                 LocalDate startDate,
                                 LocalDate endDate) {
        if (locations == null || locations.isEmpty() || locations.size() > 5) {
            throw new IllegalArgumentException("Open-Meteo bulk requests require between one and five locations");
        }
        Set<OpenMeteoLocation> distinct = new HashSet<>(locations);
        if (distinct.size() != locations.size()) {
            throw new IllegalArgumentException("Open-Meteo bulk request locations must be distinct");
        }
        if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("Open-Meteo request date range is invalid");
        }
    }

    private String coordinateParameter(List<OpenMeteoLocation> locations, boolean latitude) {
        return locations.stream()
                .map(location -> String.format(Locale.ROOT, "%.1f",
                        latitude ? location.latitude() : location.longitude()))
                .collect(Collectors.joining(","));
    }

    private JsonNode requiredArray(JsonNode hourly, String fieldName, int locationIndex) {
        JsonNode value = hourly.get(fieldName);
        if (value == null || !value.isArray()) {
            throw new OpenMeteoResponseException("Open-Meteo " + fieldName
                    + " at location " + locationIndex + " is not an array");
        }
        return value;
    }

    private void requireLength(JsonNode values, int expected, String fieldName, int locationIndex) {
        if (values.size() != expected) {
            throw new OpenMeteoResponseException("Open-Meteo " + fieldName + " at location "
                    + locationIndex + " has " + values.size() + " entries; expected " + expected);
        }
    }

    private Double nullableDouble(JsonNode value, String fieldName, int locationIndex, int hourIndex) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw invalidValue(fieldName, locationIndex, hourIndex);
        }
        return value.doubleValue();
    }

    private Integer nullableInteger(JsonNode value, String fieldName, int locationIndex, int hourIndex) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalidValue(fieldName, locationIndex, hourIndex);
        }
        return value.intValue();
    }

    private OpenMeteoResponseException invalidValue(String fieldName, int locationIndex, int hourIndex) {
        return new OpenMeteoResponseException("Open-Meteo " + fieldName + " at location "
                + locationIndex + ", index " + hourIndex + " is not numeric or null");
    }
}

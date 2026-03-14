package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo;
import berlin.tu.cyclinginfrastructurebackend.domain.SegmentExternalFactor;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ExternalFactorType;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentExternalFactorRepository;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.ExternalDataProvider;
import berlin.tu.cyclinginfrastructurebackend.service.dto.OpenMeteoResponse;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
/**
 * Fetches historical hourly weather data from Open-Meteo's Archive API and stores it
 * as {@link SegmentExternalFactor} records with factorType WEATHER.
 */
@Component
public class WeatherDataProvider implements ExternalDataProvider {
    private static final Logger log = LoggerFactory.getLogger(WeatherDataProvider.class);
    private static final String SOURCE = "open-meteo";
    private static final String BASE_URL = "https://archive-api.open-meteo.com";
    private static final ZoneId BERLIN_ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final long ONE_HOUR_MILLIS = 3_600_000L;
    private final RestClient restClient;
    private final SegmentExternalFactorRepository factorRepository;
    public WeatherDataProvider(RestClient.Builder restClientBuilder,
                               SegmentExternalFactorRepository factorRepository) {
        this.restClient = restClientBuilder.baseUrl(BASE_URL).build();
        this.factorRepository = factorRepository;
    }
    @Override
    public void enrichSegment(StreetSegment segment, Long fromEpochMillis, Long toEpochMillis) {
        Coordinate centroid = getCentroid(segment);
        if (centroid == null) {
            log.warn("Segment {} has no geometry, skipping weather enrichment.", segment.getId());
            return;
        }
        String startDate = epochMillisToDate(fromEpochMillis);
        String endDate = epochMillisToDate(toEpochMillis);
        OpenMeteoResponse response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/archive")
                            .queryParam("latitude", String.format(Locale.ROOT, "%.2f", centroid.y))
                            .queryParam("longitude", String.format(Locale.ROOT, "%.2f", centroid.x))
                            .queryParam("start_date", startDate)
                            .queryParam("end_date", endDate)
                            .queryParam("hourly", "temperature_2m,precipitation,wind_speed_10m,weather_code")
                            .queryParam("timezone", "Europe/Berlin")
                            .build())
                    .retrieve()
                    .body(OpenMeteoResponse.class);
        } catch (Exception e) {
            log.error("Open-Meteo API call failed for segment {} ({},{}): {}",
                    segment.getId(), centroid.y, centroid.x, e.getMessage());
            return;
        }
        if (response == null || response.hourly() == null || response.hourly().time() == null) {
            log.warn("Empty response from Open-Meteo for segment {}.", segment.getId());
            return;
        }
        List<SegmentExternalFactor> factors = mapResponseToFactors(segment, response, fromEpochMillis, toEpochMillis);
        if (!factors.isEmpty()) {
            factorRepository.saveAll(factors);
            log.debug("Saved {} weather factors for segment {}.", factors.size(), segment.getId());
        }
    }
    private List<SegmentExternalFactor> mapResponseToFactors(StreetSegment segment,
                                                              OpenMeteoResponse response,
                                                              Long fromEpochMillis,
                                                              Long toEpochMillis) {
        OpenMeteoResponse.HourlyData hourly = response.hourly();
        List<SegmentExternalFactor> factors = new ArrayList<>();
        for (int i = 0; i < hourly.time().size(); i++) {
            Long validFrom = parseHourToEpochMillis(hourly.time().get(i));
            if (validFrom == null) continue;

            // keep data in the [from, to) window
            if (validFrom < fromEpochMillis || validFrom >= toEpochMillis) {
                continue;
            }
            // skip if we already have weather data for this segment + hour
            if (factorRepository.existsBySegmentIdAndFactorTypeAndValidFrom(
                    segment.getId(), ExternalFactorType.WEATHER, validFrom)) {
                continue;
            }
            Map<String, Object> metadata = buildMetadata(hourly, i);
            if (metadata == null) continue;
            SegmentExternalFactor factor = new SegmentExternalFactor();
            factor.setSegment(segment);
            factor.setFactorType(ExternalFactorType.WEATHER);
            factor.setSource(SOURCE);
            factor.setValidFrom(validFrom);
            factor.setValidTo(validFrom + ONE_HOUR_MILLIS);
            factor.setMetadata(metadata);
            factors.add(factor);
        }
        return factors;
    }
    private Map<String, Object> buildMetadata(OpenMeteoResponse.HourlyData hourly, int index) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (hourly.temperature2m() != null && index < hourly.temperature2m().size()) {
            data.put("temperature_2m", hourly.temperature2m().get(index));
        }
        if (hourly.precipitation() != null && index < hourly.precipitation().size()) {
            data.put("precipitation", hourly.precipitation().get(index));
        }
        if (hourly.windSpeed10m() != null && index < hourly.windSpeed10m().size()) {
            data.put("wind_speed_10m", hourly.windSpeed10m().get(index));
        }
        if (hourly.weatherCode() != null && index < hourly.weatherCode().size()) {
            data.put("weather_code", hourly.weatherCode().get(index));
        }
        return data.isEmpty() ? null : data;
    }
    private Coordinate getCentroid(StreetSegment segment) {
        LineString geom = segment.getGeometry();
        if (geom == null || geom.isEmpty()) return null;
        return geom.getCentroid().getCoordinate();
    }
    private String epochMillisToDate(Long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(BERLIN_ZONE).toLocalDate().format(DATE_FMT);
    }
    /** Parses "2024-01-10T15:00" (ISO local datetime from Open-Meteo) to epoch millis in Berlin timezone. */
    private Long parseHourToEpochMillis(String isoLocalDateTime) {
        try {
            return java.time.LocalDateTime.parse(isoLocalDateTime)
                    .atZone(BERLIN_ZONE)
                    .toInstant()
                    .toEpochMilli();
        } catch (DateTimeParseException e) {
            log.warn("Failed to parse Open-Meteo time '{}': {}", isoLocalDateTime, e.getMessage());
            return null;
        }
    }
}

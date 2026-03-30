package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentAvoidance;
import berlin.tu.cyclinginfrastructurebackend.domain.SegmentExternalFactor;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ExternalFactorType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.WindExposure;
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
import java.util.Optional;

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
    private static final double HEADWIND_MAX_ANGLE_DEGREES = 45.0;
    private static final double TAILWIND_MIN_ANGLE_DEGREES = 135.0;

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
                            .queryParam("hourly", "temperature_2m,precipitation,wind_speed_10m,wind_direction_10m,weather_code")
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

    /**
     * Populates a recorded avoidance event with the hourly weather snapshot for the hour in which
     * the avoidance happened. If the hourly weather factor is not stored yet, it is fetched on demand.
     * <p>
     * Besides copying the raw weather values, this also derives the rider's wind exposure by comparing
     * the shortest-path travel bearing with the meteorological wind direction.
     *
     * @param avoidance the avoidance event to enrich
     */
    public void enrichAvoidance(SegmentAvoidance avoidance) {
        if (avoidance == null || avoidance.getSegment() == null || avoidance.getAvoidedAt() == null) {
            log.warn("Avoidance is missing segment or timestamp, skipping weather enrichment.");
            return;
        }

        long hourStart = avoidance.getAvoidedAt() - (avoidance.getAvoidedAt() % ONE_HOUR_MILLIS);
        ensureWeatherDataExists(avoidance.getSegment(), hourStart);

        Optional<SegmentExternalFactor> weatherFactor = factorRepository.findFirstBySegmentIdAndFactorTypeAndValidFrom(
                avoidance.getSegment().getId(),
                ExternalFactorType.WEATHER,
                hourStart
        );

        if (weatherFactor.isEmpty() || weatherFactor.get().getMetadata() == null) {
            log.warn("No weather factor available for segment {} at {}.", avoidance.getSegment().getId(), hourStart);
            return;
        }

        applyWeatherToAvoidance(avoidance, weatherFactor.get().getMetadata());
    }

    private List<SegmentExternalFactor> mapResponseToFactors(StreetSegment segment,
                                                             OpenMeteoResponse response,
                                                             Long fromEpochMillis,
                                                             Long toEpochMillis) {
        OpenMeteoResponse.HourlyData hourly = response.hourly();
        List<SegmentExternalFactor> factors = new ArrayList<>();

        for (int i = 0; i < hourly.time().size(); i++) {
            Long validFrom = parseHourToEpochMillis(hourly.time().get(i));
            if (validFrom == null) {
                continue;
            }

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
            if (metadata == null) {
                continue;
            }

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
        if (hourly.windDirection10m() != null && index < hourly.windDirection10m().size()) {
            data.put("wind_direction_10m", hourly.windDirection10m().get(index));
        }
        if (hourly.weatherCode() != null && index < hourly.weatherCode().size()) {
            data.put("weather_code", hourly.weatherCode().get(index));
        }
        return data.isEmpty() ? null : data;
    }

    /**
     * Ensures that the repository contains one weather factor for the given segment and hour.
     * Weather is cached per segment/hour so multiple avoidances in the same time slot can reuse it.
     *
     * @param segment the segment for which weather should exist
     * @param hourStart the inclusive start of the hourly bucket in epoch millis
     */
    private void ensureWeatherDataExists(StreetSegment segment, long hourStart) {
        boolean alreadyPresent = factorRepository.existsBySegmentIdAndFactorTypeAndValidFrom(
                segment.getId(),
                ExternalFactorType.WEATHER,
                hourStart
        );

        if (!alreadyPresent) {
            enrichSegment(segment, hourStart, hourStart + ONE_HOUR_MILLIS);
        }
    }

    /**
     * Copies weather values from a stored weather factor onto the avoidance entity and, when both
     * travel direction and wind direction are known, derives the relative wind angle and exposure type.
     *
     * @param avoidance the avoidance entity being enriched
     * @param metadata weather values previously stored in the external factor metadata
     */
    private void applyWeatherToAvoidance(SegmentAvoidance avoidance, Map<String, Object> metadata) {
        Double temperature2m = asDouble(metadata.get("temperature_2m"));
        Double precipitation = asDouble(metadata.get("precipitation"));
        Double windSpeed10m = asDouble(metadata.get("wind_speed_10m"));
        Double windDirection10m = asDouble(metadata.get("wind_direction_10m"));
        Integer weatherCode = asInteger(metadata.get("weather_code"));

        avoidance.setTemperature2m(temperature2m);
        avoidance.setPrecipitation(precipitation);
        avoidance.setWindSpeed10m(windSpeed10m);
        avoidance.setWindDirection10m(windDirection10m);
        avoidance.setWeatherCode(weatherCode);

        Double shortestPathBearing = avoidance.getShortestPathBearingDegrees();
        if (shortestPathBearing == null || windDirection10m == null) {
            avoidance.setRelativeWindAngleDegrees(null);
            avoidance.setWindExposure(null);
            return;
        }

        double relativeAngle = calculateRelativeWindAngle(shortestPathBearing, windDirection10m);
        avoidance.setRelativeWindAngleDegrees(relativeAngle);
        avoidance.setWindExposure(classifyWindExposure(relativeAngle));
    }

    /**
     * Calculates the smallest angle between the rider's travel direction and the direction
     * the wind is coming from. With this convention, {@code 0} means headwind and
     * {@code 180} means tailwind.
     *
     * @param travelBearingDegrees the rider's travel direction on the shortest path
     * @param windFromDegrees the meteorological wind direction, i.e., where the wind comes from
     * @return the absolute relative angle in degrees in the range {@code [0, 180]}
     */
    private double calculateRelativeWindAngle(double travelBearingDegrees, double windFromDegrees) {
        return Math.abs(normalizeSignedDegrees(windFromDegrees - travelBearingDegrees));
    }

    private WindExposure classifyWindExposure(double relativeAngleDegrees) {
        if (relativeAngleDegrees <= HEADWIND_MAX_ANGLE_DEGREES) {
            return WindExposure.HEADWIND;
        }
        if (relativeAngleDegrees >= TAILWIND_MIN_ANGLE_DEGREES) {
            return WindExposure.TAILWIND;
        }
        return WindExposure.CROSSWIND;
    }

    /**
     * Wraps an angle into the symmetric range {@code [-180, 180)} so angle differences can be
     * compared without special handling around the {@code 0/360} boundary.
     *
     * @param degrees the raw angle delta
     * @return the equivalent signed angle
     */
    private double normalizeSignedDegrees(double degrees) {
        return ((degrees + 540.0) % 360.0) - 180.0;
    }

    private Double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException e) {
                log.debug("Failed to parse double '{}': {}", text, e.getMessage());
            }
        }
        return null;
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                log.debug("Failed to parse integer '{}': {}", text, e.getMessage());
            }
        }
        return null;
    }

    private Coordinate getCentroid(StreetSegment segment) {
        LineString geom = segment.getGeometry();
        if (geom == null || geom.isEmpty()) {
            return null;
        }
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

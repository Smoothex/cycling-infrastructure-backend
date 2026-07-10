package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayLocation;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayType;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.ApiRateLimitException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Service
public class OhsomeApiDataProvider {

    private static final Logger log = LoggerFactory.getLogger(OhsomeApiDataProvider.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.of("UTC"));

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ohsome.api.url:https://api.ohsome.org/v1}")
    private String apiUrl;

    public OhsomeApiDataProvider(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
        objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public void enrichEvent(SegmentEvent event) {
        Point centroid = event.getSegment().getGeometry().getCentroid();
        Coordinate coord = centroid.getCoordinate();
        String time = ISO_FORMATTER.format(Instant.ofEpochMilli(event.getEventTimestamp()));

        // Query ohsome API for elements around the centroid at the specific time
        String bcircles = String.format(java.util.Locale.ROOT, "%f,%f,15", coord.x, coord.y); // 15m radius

        try {
            String responseBody = restClient.post()
                    .uri(apiUrl + "/elements/geometry")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("bcircles=" + bcircles +
                            "&time=" + time +
                            "&filter=type:way+and+highway=*" +
                            "&properties=tags")
                    .retrieve()
                    .body(String.class);

            if (log.isDebugEnabled()) {
                log.debug("Ohsome API response for event {}: {}", event.getId(), responseBody);
            }

            OhsomeResponse response = objectMapper.readValue(responseBody, OhsomeResponse.class);

            if (response != null && response.features != null && !response.features.isEmpty()) {
                Feature feature = withMatchingStreetName(response.features, event.getSegment());

                if (feature.properties != null) {
                    Map<String, Object> props = feature.properties;
                    
                    // Normalize deprecated OSM tags before extraction
                    normalizeCyclewayTags(props);
                    
                    // Extract basic attributes
                    event.setSurface((String) props.get("surface"));
                    event.setSmoothness((String) props.get("smoothness"));
                    event.setLit((String) props.get("lit"));
                    event.setHighway((String) props.get("highway"));
                    
                    // Extract cycleway information
                    extractCyclewayInfo(props, event);
                }
            }
        } catch (RestClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
                Instant retryAt = nextUtcDay();
                log.warn("Ohsome API returned 403 Forbidden for event {}; the server has likely "
                        + "blocked this client for excessive use. Pausing until {}.",
                        event.getId(), retryAt);
                throw new ApiRateLimitException("Ohsome API access forbidden (client blocked)", retryAt, e);
            }
            if (isRateLimited(e)) {
                Instant retryAt = parseRetryAfter(e);
                log.warn("Ohsome API rate limit hit for event {}: {}. Backing off{}.",
                        event.getId(), e.getMessage(), retryAt != null ? " until " + retryAt : "");
                throw new ApiRateLimitException("Ohsome API rate limit exceeded", retryAt, e);
            }
            log.error("Failed to query ohsome API for event {}: {}", event.getId(), e.getMessage());
            throw new RuntimeException("Ohsome API call failed", e);
        } catch (Exception e) {
            log.error("Failed to query ohsome API for event {}: {}", event.getId(), e.getMessage());
            throw new RuntimeException("Ohsome API call failed", e);
        }
    }

    /** The public ohsome instance signals overload with 429 or 503 rather than a documented quota. */
    private boolean isRateLimited(RestClientResponseException e) {
        return e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS
                || e.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE;
    }

    /**
     * A 403 from the public ohsome instance is its web server blocking the client
     * (typically an IP-level block after excessive use), not a per-request failure;
     * continuing to send requests only reinforces the block. There is no documented
     * reset time, so wait until the next UTC day before probing again.
     *
     * @return the start of the next UTC day, with a small buffer
     */
    private Instant nextUtcDay() {
        return Instant.now().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS)
                .plusDays(1).toInstant().plus(Duration.ofMinutes(5));
    }

    /**
     * Parses the Retry-After response header, which may hold either delay seconds
     * or an HTTP date.
     *
     * @return the instant to retry at, or null if the header is absent or malformed
     */
    private Instant parseRetryAfter(RestClientResponseException e) {
        String retryAfter = e.getResponseHeaders() == null
                ? null
                : e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (retryAfter == null || retryAfter.isBlank()) {
            return null;
        }
        try {
            return Instant.now().plusSeconds(Long.parseLong(retryAfter.trim()));
        } catch (NumberFormatException ignored) {
            // not a seconds value, try HTTP date format
        }
        try {
            return ZonedDateTime.parse(retryAfter.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ex) {
            log.debug("Unparseable Retry-After header '{}'.", retryAfter);
            return null;
        }
    }

    private Feature withMatchingStreetName(List<Feature> features, StreetSegment segment) {
        return features.stream()
                .filter(f -> segment.getStreetName() != null &&
                        segment.getStreetName().equals(f.properties.get("name")))
                .findFirst()
                .orElse(features.isEmpty() ? null : features.getFirst()); // Fallback
    }

    /**
     * Normalizes deprecated/discouraged OSM cycleway tags to modern equivalents.    <p>
     * See {@link <a href="https://wiki.openstreetmap.org/wiki/Key:cycleway#Deprecated_or_discouraged_tags">Key:cycleway in OSM Wiki</a>}
     */
    private void normalizeCyclewayTags(Map<String, Object> props) {
        String cycleway = (String) props.get("cycleway");
        
        if (cycleway == null) return;
        
        switch (cycleway) {
            case "opposite":
                // cycleway:opposite -> oneway:bicycle=no + cycleway=no
                if (!props.containsKey("oneway:bicycle")) {
                    props.put("oneway:bicycle", "no");
                }
                props.put("cycleway", "no");
                break;
                
            case "opposite_lane":
                // cycleway:opposite_lane -> oneway:bicycle=no + cycleway=lane
                if (!props.containsKey("oneway:bicycle")) {
                    props.put("oneway:bicycle", "no");
                }
                props.put("cycleway", "lane");
                break;
                
            case "opposite_track":
                // cycleway:opposite_track -> oneway:bicycle=no + cycleway=track
                if (!props.containsKey("oneway:bicycle")) {
                    props.put("oneway:bicycle", "no");
                }
                props.put("cycleway", "track");
                break;
        }
    }

    private void extractCyclewayInfo(Map<String, Object> props, SegmentEvent event) {
        String cyclewayValue = null;
        CyclewayLocation location = CyclewayLocation.NONE;
        
        if (props.containsKey("cycleway:both")) {
            cyclewayValue = (String) props.get("cycleway:both");
            location = CyclewayLocation.BOTH;
        } else if (props.containsKey("cycleway:right")) {
            cyclewayValue = (String) props.get("cycleway:right");
            location = CyclewayLocation.RIGHT;
        } else if (props.containsKey("cycleway:left")) {
            cyclewayValue = (String) props.get("cycleway:left");
            location = CyclewayLocation.LEFT;
        } else if (props.containsKey("cycleway")) {
            cyclewayValue = (String) props.get("cycleway");
            location = CyclewayLocation.UNKNOWN;
        }
        
        // Map to enum
        CyclewayType type = cyclewayValue != null
            ? CyclewayType.fromOsmValue(cyclewayValue)
            : null;
        
        event.setCyclewayType(type);
        event.setCyclewayLocation(location);

        // Extract surface and width
        if (type != null && type != CyclewayType.NO) {
            String surface = extractCyclewaySurface(props, location);
            event.setCyclewaySurface(surface);
            
            Double width = extractCyclewayWidth(props, location);
            event.setCyclewayWidth(width);
        }
        
        // Extract bicycle oneway information
        Boolean bicycleOneway = extractBicycleOneway(props);
        event.setBicycleOneway(bicycleOneway);
    }

    private String extractCyclewaySurface(Map<String, Object> props, CyclewayLocation location) {
        String surface = switch (location) {
            case BOTH -> (String) props.get("cycleway:both:surface");
            case RIGHT -> (String) props.get("cycleway:right:surface");
            case LEFT -> (String) props.get("cycleway:left:surface");
            case UNKNOWN -> (String) props.get("cycleway:surface");
            default -> null;
        };

        // Fallback to road surface if no cycleway-specific surface
        if (surface == null) {
            surface = (String) props.get("surface");
        }
        
        return surface;
    }

    private Double extractCyclewayWidth(Map<String, Object> props, CyclewayLocation location) {
        String widthStr = switch (location) {
            case BOTH -> (String) props.get("cycleway:both:width");
            case RIGHT -> (String) props.get("cycleway:right:width");
            case LEFT -> (String) props.get("cycleway:left:width");
            case UNKNOWN -> (String) props.get("cycleway:width");
            default -> null;
        };

        if (widthStr != null) {
            try {
                return Double.parseDouble(widthStr);
            } catch (NumberFormatException e) {
                log.warn("Invalid cycleway width value: {}", widthStr);
            }
        }
        
        return null;
    }

    private Boolean extractBicycleOneway(Map<String, Object> props) {
        String onewayBicycle = (String) props.get("oneway:bicycle");
        
        if (onewayBicycle == null) {
            return null; // Not specified
        }
        
        // bike riders must follow one direction
        if ("yes".equals(onewayBicycle)) {
            return true;
        }
        
        // bike riders can go in both directions
        if ("no".equals(onewayBicycle) || "-1".equals(onewayBicycle)) {
            return false;
        }
        
        return null;
    }

    // DTOs for JSON parsing
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record OhsomeResponse(String type, List<Feature> features) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Feature(String type, java.util.Map<String, Object> properties) {}
}

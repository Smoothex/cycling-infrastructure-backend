package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

            log.info("Ohsome API response for event {}: {}", event.getId(), responseBody);

            OhsomeResponse response = objectMapper.readValue(responseBody, OhsomeResponse.class);

            if (response != null && response.features != null && !response.features.isEmpty()) {
                Feature feature = withMatchingStreetName(response.features, event.getSegment());

                if (feature.properties != null) {
                    Map<String, Object> props = feature.properties;
                    event.setSurface((String) props.get("surface"));
                    event.setSmoothness((String) props.get("smoothness"));
                    event.setLit((String) props.get("lit"));
                    event.setHighway((String) props.get("highway"));
                    event.setCycleway((String) props.get("cycleway"));
                }
            }
        } catch (Exception e) {
            log.error("Failed to query ohsome API for event {}: {}", event.getId(), e.getMessage());
            throw new RuntimeException("Ohsome API call failed", e);
        }
    }

    private Feature withMatchingStreetName(List<Feature> features, StreetSegment segment) {
        return features.stream()
                .filter(f -> segment.getStreetName() != null &&
                        segment.getStreetName().equals(f.properties.get("name")))
                .findFirst()
                .orElse(features.isEmpty() ? null : features.getFirst()); // Fallback
    }

    // DTOs for JSON parsing
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record OhsomeResponse(String type, List<Feature> features) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    record Feature(String type, java.util.Map<String, Object> properties) {}
}

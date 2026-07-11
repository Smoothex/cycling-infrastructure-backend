package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.VIZ.RoadClosures;

import berlin.tu.cyclinginfrastructurebackend.domain.RoadClosure;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ExternalFactorType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RoadClosureSeverity;
import berlin.tu.cyclinginfrastructurebackend.repository.RoadClosureRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Imports the VIZ (Verkehrsinformationszentrale Berlin) Baustellen/Sperrungen feed
 * into the {@code road_closures} table. The feed is a live snapshot of current and
 * planned entries, so imports upsert by feed id and never delete: rows that drop out
 * of later feed versions remain as history. The download is cached on disk so
 * restarts can fall back to the last successful copy when the API is unreachable.
 */
@Service
public class RoadClosureImportService {

    private static final Logger log = LoggerFactory.getLogger(RoadClosureImportService.class);
    private static final String DEFAULT_DATA_URL =
            "https://api.viz.berlin.de/daten/baustellen_sperrungen_viz.json";
    private static final ZoneId BERLIN_ZONE = ZoneId.of("Europe/Berlin");
    static final DateTimeFormatter BERLIN_DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final RoadClosureRepository roadClosureRepository;
    private final RestClient restClient;
    private final String dataUrl;
    private final Path cacheFile;
    private boolean importAttempted = false;

    public RoadClosureImportService(RoadClosureRepository roadClosureRepository,
                                    RestClient.Builder restClientBuilder,
                                    @Value("${enrichment.berlin-open-data.url:" + DEFAULT_DATA_URL + "}") String dataUrl,
                                    @Value("${enrichment.berlin-open-data.cache-file:./data/berlinOpenData/cache/baustellen_sperrungen_viz.json}") String cacheFile) {
        this.roadClosureRepository = roadClosureRepository;
        this.restClient = restClientBuilder.build();
        this.dataUrl = dataUrl;
        this.cacheFile = Path.of(cacheFile);
    }

    /**
     * Runs {@link #refresh()} once per application run and reports whether any
     * road closures are available afterwards (from this import or earlier runs).
     */
    public synchronized boolean ensureImported() {
        if (!importAttempted) {
            importAttempted = true;
            refresh();
        }
        return roadClosureRepository.count() > 0;
    }

    @Scheduled(
            fixedDelayString = "${enrichment.road-closures.refresh-ms:86400000}",
            initialDelayString = "${enrichment.road-closures.refresh-ms:86400000}")
    public synchronized void refresh() {
        String json = fetchGeoJson();
        if (json == null) {
            log.warn("No VIZ road-closure data available; keeping existing road_closures rows.");
            return;
        }

        try {
            JsonNode features = new ObjectMapper().readTree(json).get("features");
            if (features == null || !features.isArray()) {
                log.warn("No 'features' array in VIZ road-closure GeoJSON from '{}'.", dataUrl);
                return;
            }

            GeoJsonReader geoJsonReader = new GeoJsonReader();
            long now = System.currentTimeMillis();
            int inserted = 0;
            int updated = 0;
            int skipped = 0;

            for (JsonNode feature : features) {
                try {
                    RoadClosure parsed = parseFeature(feature, geoJsonReader, now);
                    if (parsed == null) {
                        skipped++;
                        continue;
                    }
                    RoadClosure existing = roadClosureRepository.findByFeedId(parsed.getFeedId()).orElse(null);
                    if (existing == null) {
                        roadClosureRepository.save(parsed);
                        inserted++;
                    } else {
                        copyFeedFields(parsed, existing);
                        existing.setLastSeenAt(now);
                        roadClosureRepository.save(existing);
                        updated++;
                    }
                } catch (Exception e) {
                    skipped++;
                    log.debug("Skipping road-closure feature: {}", e.getMessage());
                }
            }
            log.info("VIZ road-closure import: {} inserted, {} updated, {} skipped.", inserted, updated, skipped);
        } catch (IOException e) {
            log.error("Failed to parse VIZ road-closure GeoJSON: {}", e.getMessage());
        }
    }

    /**
     * Downloads the current VIZ dataset and refreshes the local cache copy; if the
     * download fails, falls back to the cached copy from a previous run.
     *
     * @return the GeoJSON document, or null if neither download nor cache is available
     */
    private String fetchGeoJson() {
        try {
            // The VIZ API serves application/octet-stream without a charset; decoding
            // via body(String.class) would fall back to ISO-8859-1 and garble umlauts,
            // so fetch raw bytes and decode as UTF-8 (JSON's mandated encoding).
            byte[] bytes = restClient.get().uri(dataUrl).retrieve().body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                throw new IllegalStateException("empty response");
            }
            String body = new String(bytes, StandardCharsets.UTF_8);
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, body);
            log.info("Downloaded VIZ road-closure data from '{}' ({} bytes).", dataUrl, bytes.length);
            return body;
        } catch (Exception e) {
            log.warn("Failed to download VIZ road-closure data from '{}': {}", dataUrl, e.getMessage());
        }

        if (Files.exists(cacheFile)) {
            try {
                String body = Files.readString(cacheFile);
                log.info("Using cached VIZ road-closure data from '{}'.", cacheFile);
                return body;
            } catch (IOException e) {
                log.warn("Failed to read cached VIZ road-closure data '{}': {}", cacheFile, e.getMessage());
            }
        }
        return null;
    }

    private RoadClosure parseFeature(JsonNode feature, GeoJsonReader reader, long now) throws Exception {
        JsonNode props = feature.get("properties");
        JsonNode geomNode = feature.get("geometry");
        if (props == null || geomNode == null || geomNode.isNull()) {
            return null;
        }

        String feedId = textOrNull(props, "id");
        if (feedId == null || feedId.isBlank()) {
            return null;
        }

        Geometry geometry = reader.read(geomNode.toString());
        if (geometry == null || geometry.isEmpty()) {
            return null;
        }

        JsonNode validityNode = props.get("validity");
        Long validFrom = validityNode == null || validityNode.isNull()
                ? null
                : parseBerlinDateTime(textOrNull(validityNode, "from"));
        if (validFrom == null) {
            return null;
        }
        Long validTo = parseBerlinDateTime(textOrNull(validityNode, "to"));

        RoadClosure closure = new RoadClosure();
        closure.setFeedId(feedId);
        closure.setLmsId(textOrNull(props, "lms_id"));
        closure.setFactorType(mapSubtype(textOrNull(props, "subtype")));
        closure.setSeverity(RoadClosureSeverity.fromLabel(textOrNull(props, "severity")));
        closure.setDirection(textOrNull(props, "direction"));
        closure.setStreet(textOrNull(props, "street"));
        closure.setSection(textOrNull(props, "section"));
        closure.setContent(textOrNull(props, "content"));
        closure.setValidFrom(validFrom);
        closure.setValidTo(validTo);
        closure.setGeometry(geometry);
        closure.setTstore(parseInstant(textOrNull(props, "tstore")));
        closure.setFirstSeenAt(now);
        closure.setLastSeenAt(now);
        return closure;
    }

    private void copyFeedFields(RoadClosure source, RoadClosure target) {
        target.setLmsId(source.getLmsId());
        target.setFactorType(source.getFactorType());
        target.setSeverity(source.getSeverity());
        target.setDirection(source.getDirection());
        target.setStreet(source.getStreet());
        target.setSection(source.getSection());
        target.setContent(source.getContent());
        target.setValidFrom(source.getValidFrom());
        target.setValidTo(source.getValidTo());
        target.setGeometry(source.getGeometry());
        target.setTstore(source.getTstore());
    }

    public static ExternalFactorType mapSubtype(String subtype) {
        if (subtype == null) return ExternalFactorType.ROAD_CLOSURE;
        return switch (subtype) {
            case "Baustelle", "Bauarbeiten" -> ExternalFactorType.CONSTRUCTION;
            case "Sperrung" -> ExternalFactorType.ROAD_CLOSURE;
            case "Störung" -> ExternalFactorType.EVENT;
            case "Gefahr" -> ExternalFactorType.HAZARD;
            case "Unfall" -> ExternalFactorType.INCIDENT;
            default -> ExternalFactorType.ROAD_CLOSURE;
        };
    }

    /**
     * Parses a Berlin-local timestamp. The live VIZ API uses ISO local date-time
     * ("2025-07-23T07:00"); older Open Data portal snapshots use "dd.MM.yyyy HH:mm".
     */
    public static Long parseBerlinDateTime(String text) {
        if (text == null || text.isBlank()) return null;
        for (DateTimeFormatter format : List.of(DateTimeFormatter.ISO_LOCAL_DATE_TIME, BERLIN_DATE_FMT)) {
            try {
                return LocalDateTime.parse(text, format)
                        .atZone(BERLIN_ZONE)
                        .toInstant()
                        .toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        log.debug("Failed to parse Berlin date '{}'.", text);
        return null;
    }

    private static Long parseInstant(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }
}

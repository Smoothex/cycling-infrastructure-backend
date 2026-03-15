package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.BerlinOpenData;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentExternalFactor;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ExternalFactorType;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentExternalFactorRepository;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.ExternalDataProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

 /**
 * Reads Berlin Open Data "baustellen_sperrungen.json" (construction sites, road closures,
 * events, hazards, incidents) once at startup into a JTS {@link STRtree} spatial index.
 * <p>
 * On each {@link #enrichSegment} the STRtree is queried by the street segment's bounding box,
 * then candidates are filtered by spatial proximity and temporal
 * overlap with the avoidance time window.
 */
@Component
public class RoadClosureDataProvider implements ExternalDataProvider {

    private static final Logger log = LoggerFactory.getLogger(RoadClosureDataProvider.class);
    private static final String SOURCE = "berlin-open-data";
    private static final ZoneId BERLIN_ZONE = ZoneId.of("Europe/Berlin");
    static final double PROXIMITY_DEGREES = 0.0003; // around 30m
    static final DateTimeFormatter BERLIN_DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private final SegmentExternalFactorRepository factorRepository;
    private final String filePath;

    /** Spatial index built once at startup. Entries are {@link RoadClosureEntry}. */
    private STRtree spatialIndex;
    private boolean indexReady = false;

    @Autowired
    public RoadClosureDataProvider(SegmentExternalFactorRepository factorRepository,
                                   @Value("${enrichment.berlin-open-data.file-path}") String filePath) {
        this.factorRepository = factorRepository;
        this.filePath = filePath;
    }

    @PostConstruct
    void buildIndex() {
        if (filePath == null) return;

        File file = new File(filePath);
        if (!file.exists()) {
            log.warn("Berlin Open Data file not found at '{}'. Road-closure enrichment disabled.", filePath);
            return;
        }

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode root = objectMapper.readTree(file);
            JsonNode features = root.get("features");
            if (features == null || !features.isArray()) {
                log.warn("No 'features' array in GeoJSON file '{}'.", filePath);
                return;
            }

            STRtree tree = new STRtree();
            GeoJsonReader geoJsonReader = new GeoJsonReader();
            int loaded = 0;
            int skipped = 0;

            for (JsonNode feature : features) {
                try {
                    RoadClosureEntry entry = parseFeature(feature, geoJsonReader);
                    if (entry != null) {
                        tree.insert(entry.geometry().getEnvelopeInternal(), entry);
                        loaded++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    skipped++;
                    log.debug("Skipping feature: {}", e.getMessage());
                }
            }

            tree.build();
            this.spatialIndex = tree;
            this.indexReady = true;
            log.info("Berlin Open Data spatial index built: {} features loaded, {} skipped.", loaded, skipped);
        } catch (IOException e) {
            log.error("Failed to read Berlin Open Data GeoJSON '{}': {}", filePath, e.getMessage());
        }
    }

    @Override
    public void enrichSegment(StreetSegment segment, Long fromEpochMillis, Long toEpochMillis) {
        if (!indexReady || spatialIndex == null) {
            return;
        }

        LineString geom = segment.getGeometry();
        if (geom == null || geom.isEmpty()) {
            log.debug("Segment {} has no geometry, skipping road-closure enrichment.", segment.getId());
            return;
        }

        @SuppressWarnings("unchecked")
        List<RoadClosureEntry> candidates = spatialIndex.query(geom.getEnvelopeInternal());

        List<SegmentExternalFactor> factors = new ArrayList<>();

        for (RoadClosureEntry entry : candidates) {
            // Proximity filter
            if (!geom.isWithinDistance(entry.geometry(), PROXIMITY_DEGREES)) {
                continue;
            }

            // Temporal filter
            if (!temporallyOverlaps(entry, fromEpochMillis, toEpochMillis)) {
                continue;
            }

            // Street name filter
            if (!isStreetNameMatch(segment.getStreetName(), entry.metadata().get("street"))) {
                continue;
            }

            // don't re-insert if we already have this factor
            if (factorRepository.existsBySegmentIdAndFactorTypeAndValidFrom(segment.getId(), entry.factorType(), entry.validFrom())) {
                continue;
            }

            SegmentExternalFactor factor = new SegmentExternalFactor();
            factor.setSegment(segment);
            factor.setFactorType(entry.factorType());
            factor.setSource(SOURCE);
            factor.setValidFrom(entry.validFrom());
            factor.setValidTo(entry.validTo());
            factor.setAffectedArea(entry.geometry());
            factor.setMetadata(entry.metadata());
            factors.add(factor);
        }

        if (!factors.isEmpty()) {
            factorRepository.saveAll(factors);
            log.debug("Saved {} road-closure factors for segment {}.", factors.size(), segment.getId());
        }
    }

    private boolean isStreetNameMatch(String streetSegmentName, Object externalFactorStreetName) {
        if (streetSegmentName == null || streetSegmentName.isBlank() || streetSegmentName.equalsIgnoreCase("Unknown")) {
            return true;
        }
        if (externalFactorStreetName == null || externalFactorStreetName.toString().isBlank()) {
            return true;
        }

        String streetSegmentName_lower = streetSegmentName.toLowerCase(Locale.GERMAN).replace("str.", "straße");
        String externalFactorStreetName_lower = externalFactorStreetName.toString().toLowerCase(Locale.GERMAN).replace("str.", "straße");

        return streetSegmentName_lower.contains(externalFactorStreetName_lower)
                || externalFactorStreetName_lower.contains(streetSegmentName_lower);
    }


    private RoadClosureEntry parseFeature(JsonNode feature, GeoJsonReader reader) throws ParseException {
        JsonNode props = feature.get("properties");
        JsonNode geomNode = feature.get("geometry");

        if (props == null || geomNode == null || geomNode.isNull()) return null;

        // Parse geometry
        Geometry geometry = reader.read(geomNode.toString());
        if (geometry == null || geometry.isEmpty()) return null;

        // Parse validity period
        JsonNode validityNode = props.get("validity");
        if (validityNode == null || validityNode.isNull()) return null;

        Long validFrom = parseBerlinDateTime(textOrNull(validityNode, "from"));
        Long validTo = parseBerlinDateTime(textOrNull(validityNode, "to"));
        if (validFrom == null || validTo == null) return null;

        // Map subtype to ExternalFactorType
        String subtype = textOrNull(props, "subtype");
        ExternalFactorType factorType = mapSubtype(subtype);

        // Build metadata
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "id", props, "id");
        putIfPresent(metadata, "subtype", props, "subtype");
        putIfPresent(metadata, "severity", props, "severity");
        putIfPresent(metadata, "direction", props, "direction");
        putIfPresent(metadata, "street", props, "street");
        putIfPresent(metadata, "section", props, "section");
        putIfPresent(metadata, "content", props, "content");

        return new RoadClosureEntry(geometry, validFrom, validTo, factorType, metadata);
    }

    public static ExternalFactorType mapSubtype(String subtype) {
        if (subtype == null) return ExternalFactorType.ROAD_CLOSURE;
        return switch (subtype) {
            case "Baustelle" -> ExternalFactorType.CONSTRUCTION;
            case "Sperrung" -> ExternalFactorType.ROAD_CLOSURE;
            case "Störung" -> ExternalFactorType.EVENT;
            case "Gefahr" -> ExternalFactorType.HAZARD;
            case "Unfall" -> ExternalFactorType.INCIDENT;
            default -> ExternalFactorType.ROAD_CLOSURE;
        };
    }

    private boolean temporallyOverlaps(RoadClosureEntry entry, Long fromEpochMillis, Long toEpochMillis) {
        if (entry.validFrom() > toEpochMillis) return false;
        return entry.validTo() == null || entry.validTo() >= fromEpochMillis;
    }

    public static Long parseBerlinDateTime(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDateTime.parse(text, BERLIN_DATE_FMT)
                    .atZone(BERLIN_ZONE)
                    .toInstant()
                    .toEpochMilli();
        } catch (DateTimeParseException e) {
            log.debug("Failed to parse Berlin date '{}': {}", text, e.getMessage());
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : null;
    }

    private static void putIfPresent(Map<String, Object> map, String key, JsonNode props, String field) {
        String value = textOrNull(props, field);
        if (value != null) {
            map.put(key, value);
        }
    }

    public record RoadClosureEntry(
            Geometry geometry,
            Long validFrom,
            Long validTo,
            ExternalFactorType factorType,
            Map<String, Object> metadata
    ) {}
}

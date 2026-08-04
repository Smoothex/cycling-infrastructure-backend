package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.VIZ.RoadClosures;

import berlin.tu.cyclinginfrastructurebackend.domain.RoadClosure;
import berlin.tu.cyclinginfrastructurebackend.domain.SegmentExternalFactor;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ExternalFactorType;
import berlin.tu.cyclinginfrastructurebackend.repository.RoadClosureRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentExternalFactorRepository;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.ExternalDataProvider;
import jakarta.annotation.PostConstruct;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

 /**
 * Enriches street segments with VIZ (Verkehrsinformationszentrale Berlin)
 * Baustellen/Sperrungen data (construction sites, road closures, events, hazards,
 * incidents). The feed itself is imported into the {@code road_closures} table by
 * {@link RoadClosureImportService}; this provider loads those rows once at startup
 * into a JTS {@link STRtree} spatial index.
 * <p>
 * On each {@link #enrichSegment} the STRtree is queried by the street segment's bounding box,
 * then candidates are filtered by spatial proximity and temporal
 * overlap with the avoidance time window.
 */
@Component
public class RoadClosureDataProvider implements ExternalDataProvider {

    private static final Logger log = LoggerFactory.getLogger(RoadClosureDataProvider.class);
    private static final String SOURCE = "berlin-open-data";
    static final double PROXIMITY_DEGREES = 0.0003; // around 30m
    private final SegmentExternalFactorRepository factorRepository;
    private final RoadClosureImportService importService;
    private final RoadClosureRepository roadClosureRepository;

    /** Spatial index built once at startup. Entries are {@link RoadClosureEntry}. */
    private STRtree spatialIndex;
    private boolean indexReady = false;

    public RoadClosureDataProvider(SegmentExternalFactorRepository factorRepository,
                                   RoadClosureImportService importService,
                                   RoadClosureRepository roadClosureRepository) {
        this.factorRepository = factorRepository;
        this.importService = importService;
        this.roadClosureRepository = roadClosureRepository;
    }

    @PostConstruct
    void buildIndex() {
        if (!importService.ensureImported()) {
            log.warn("No VIZ road-closure data available. Road-closure enrichment disabled.");
            return;
        }

        STRtree tree = new STRtree();
        int loaded = 0;
        for (RoadClosure closure : roadClosureRepository.findAll()) {
            RoadClosureEntry entry = toEntry(closure);
            if (entry == null) {
                continue;
            }
            tree.insert(entry.geometry().getEnvelopeInternal(), entry);
            loaded++;
        }

        tree.build();
        this.spatialIndex = tree;
        this.indexReady = true;
        log.info("VIZ road-closure spatial index built from {} stored closures.", loaded);
    }

    private RoadClosureEntry toEntry(RoadClosure closure) {
        if (closure.getGeometry() == null || closure.getGeometry().isEmpty() || closure.getValidFrom() == null) {
            return null;
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "id", closure.getFeedId());
        putIfPresent(metadata, "subtype", closure.getFactorType() != null ? closure.getFactorType().name() : null);
        putIfPresent(metadata, "severity", closure.getSeverity() != null ? closure.getSeverity().name() : null);
        putIfPresent(metadata, "direction", closure.getDirection());
        putIfPresent(metadata, "street", closure.getStreet());
        putIfPresent(metadata, "section", closure.getSection());
        putIfPresent(metadata, "content", closure.getContent());

        return new RoadClosureEntry(
                closure.getGeometry(),
                closure.getValidFrom(),
                closure.getValidTo(),
                closure.getFactorType() != null ? closure.getFactorType() : ExternalFactorType.ROAD_CLOSURE,
                metadata
        );
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
        Envelope queryEnvelope = new Envelope(geom.getEnvelopeInternal());
        queryEnvelope.expandBy(PROXIMITY_DEGREES);
        List<RoadClosureEntry> candidates = spatialIndex.query(queryEnvelope);

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

    private boolean temporallyOverlaps(RoadClosureEntry entry, Long fromEpochMillis, Long toEpochMillis) {
        if (entry.validFrom() > toEpochMillis) return false;
        return entry.validTo() == null || entry.validTo() >= fromEpochMillis;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
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

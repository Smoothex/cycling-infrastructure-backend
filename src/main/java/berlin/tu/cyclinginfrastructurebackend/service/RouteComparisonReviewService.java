package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.RouteComparisonReview;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonReviewIssue;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.RouteComparisonReviewRepository;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteReviewDetailDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteReviewDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteReviewRequestDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteReviewSampleDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteReviewSampleItemDto;
import com.opencsv.CSVWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RouteComparisonReviewService {

    private static final int MAX_NOTES_LENGTH = 1_000;
    private static final String[] CSV_HEADER = {
            "ride_id", "sample_order", "class_sample_rank", "automated_classification",
            "start_time", "ride_intent", "bike_type", "actual_distance_m",
            "shortest_path_distance_m", "absolute_excess_distance_m", "relative_detour_ratio",
            "overlap_ratio", "detour_threshold_ratio", "maximum_equivalent_excess_distance_m",
            "minimum_overlap_ratio", "median_gps_accuracy_m", "gps_point_count",
            "observed_route_geojson", "shortest_route_geojson", "manual_classification",
            "issue_codes", "review_notes", "reviewed_at"
    };

    private static final String SAMPLE_CTE = """
            WITH ranked_comparisons AS (
                SELECT r.id,
                       r.start_time,
                       r.end_time,
                       r.route_comparison_type,
                       r.ride_intent,
                       r.bike_type,
                       r.actual_distance,
                       r.shortest_path_distance,
                       r.actual_distance - r.shortest_path_distance AS absolute_excess_distance,
                       (r.actual_distance - r.shortest_path_distance)
                           / NULLIF(r.shortest_path_distance, 0) AS relative_detour_ratio,
                       r.overlap_ratio,
                       r.median_gps_accuracy,
                       r.gps_point_count,
                       ROW_NUMBER() OVER (
                           PARTITION BY r.route_comparison_type
                           ORDER BY MD5(CAST(r.id AS text))
                       ) AS class_sample_rank
                FROM rides r
                WHERE r.status = 'PROCESSED'
                  AND r.route_comparison_type IS NOT NULL
                  AND r.actual_distance IS NOT NULL
                  AND r.shortest_path_distance IS NOT NULL
                  AND r.overlap_ratio IS NOT NULL
                  AND r.trajectory IS NOT NULL
                  AND r.shortest_path IS NOT NULL
            ), review_sample AS (
                SELECT *
                FROM ranked_comparisons
                WHERE class_sample_rank <= :perType
            )
            """;

    private final EntityManager entityManager;
    private final RideRepository rideRepository;
    private final RouteComparisonReviewRepository reviewRepository;
    private final ObjectMapper objectMapper;
    private final int samplePerType;
    private final double detourThresholdRatio;
    private final double maximumEquivalentExcessDistanceMeters;
    private final double minimumOverlapRatio;

    public RouteComparisonReviewService(
            EntityManager entityManager,
            RideRepository rideRepository,
            RouteComparisonReviewRepository reviewRepository,
            ObjectMapper objectMapper,
            @Value("${analysis.route-review.sample-per-type:30}") int samplePerType,
            @Value("${analysis.detour.threshold:0.10}") double detourThresholdRatio,
            @Value("${analysis.detour.maximum-equivalent-excess-meters:500}")
            double maximumEquivalentExcessDistanceMeters,
            @Value("${analysis.route-overlap.minimum-ratio:0.30}") double minimumOverlapRatio) {
        this.entityManager = entityManager;
        this.rideRepository = rideRepository;
        this.reviewRepository = reviewRepository;
        this.objectMapper = objectMapper;
        this.samplePerType = samplePerType;
        this.detourThresholdRatio = detourThresholdRatio;
        this.maximumEquivalentExcessDistanceMeters = maximumEquivalentExcessDistanceMeters;
        this.minimumOverlapRatio = minimumOverlapRatio;
    }

    @Transactional(readOnly = true)
    public RouteReviewSampleDto getSample() {
        Query query = entityManager.createNativeQuery(SAMPLE_CTE + """
                SELECT sample.id,
                       sample.start_time,
                       sample.route_comparison_type,
                       sample.ride_intent,
                       sample.bike_type,
                       sample.actual_distance,
                       sample.shortest_path_distance,
                       sample.absolute_excess_distance,
                       sample.relative_detour_ratio,
                       sample.overlap_ratio,
                       sample.class_sample_rank
                FROM review_sample sample
                ORDER BY CASE sample.route_comparison_type
                    WHEN 'EQUIVALENT_ROUTE' THEN 1
                    WHEN 'LOCAL_DETOUR' THEN 2
                    WHEN 'CORRIDOR_ALTERNATIVE' THEN 3
                    ELSE 4
                END, sample.class_sample_rank
                """).setParameter("perType", samplePerType);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        Map<UUID, RouteReviewDto> reviewsByRideId = rows.isEmpty()
                ? Map.of()
                : reviewRepository.findAllByRideIdIn(
                                rows.stream().map(row -> uuid(row[0])).toList())
                        .stream()
                        .collect(Collectors.toMap(
                                review -> review.getRide().getId(),
                                RouteReviewDto::from,
                                (first, ignored) -> first));
        List<RouteReviewSampleItemDto> items = new ArrayList<>(rows.size());
        int sampleOrder = 1;
        for (Object[] row : rows) {
            UUID rideId = uuid(row[0]);
            items.add(new RouteReviewSampleItemDto(
                    rideId,
                    sampleOrder++,
                    integer(row[10]),
                    RouteComparisonType.valueOf(string(row[2])),
                    longValue(row[1]),
                    string(row[3]),
                    string(row[4]),
                    decimal(row[5]),
                    decimal(row[6]),
                    decimal(row[7]),
                    decimal(row[8]),
                    decimal(row[9]),
                    reviewsByRideId.get(rideId)
            ));
        }

        return new RouteReviewSampleDto(
                samplePerType,
                items.size(),
                items.stream().filter(item -> item.review() != null).count(),
                false,
                List.copyOf(items)
        );
    }

    @Transactional(readOnly = true)
    public RouteReviewDetailDto getDetail(UUID rideId) {
        RouteReviewSampleItemDto sampleItem = requireSampleItem(rideId);
        return buildDetail(sampleItem, true);
    }

    private RouteReviewDetailDto buildDetail(
            RouteReviewSampleItemDto sampleItem,
            boolean includeSignals) {
        UUID rideId = sampleItem.rideId();
        Query routeQuery = entityManager.createNativeQuery("""
                SELECT r.end_time,
                       r.median_gps_accuracy,
                       r.gps_point_count,
                       ST_AsGeoJSON(r.trajectory),
                       ST_AsGeoJSON(r.shortest_path)
                FROM rides r
                WHERE r.id = :rideId
                """).setParameter("rideId", rideId);
        Object[] routeRow = (Object[]) routeQuery.getSingleResult();

        List<RouteReviewDetailDto.RouteSignalDto> signals = includeSignals
                ? findSignals(rideId)
                : List.of();

        Long endTimestamp = longValue(routeRow[0]);
        Long durationSeconds = sampleItem.startTimestamp() != null && endTimestamp != null
                ? Math.max(0, (endTimestamp - sampleItem.startTimestamp()) / 1_000)
                : null;
        return new RouteReviewDetailDto(
                rideId,
                sampleItem.sampleOrder(),
                sampleItem.classSampleRank(),
                sampleItem.automatedClassification(),
                sampleItem.startTimestamp(),
                endTimestamp,
                durationSeconds,
                sampleItem.rideIntent(),
                sampleItem.bikeType(),
                sampleItem.actualDistanceMeters(),
                sampleItem.shortestPathDistanceMeters(),
                sampleItem.absoluteExcessDistanceMeters(),
                sampleItem.relativeDetourRatio(),
                sampleItem.overlapRatio(),
                decimal(routeRow[1]),
                longValue(routeRow[2]),
                detourThresholdRatio,
                maximumEquivalentExcessDistanceMeters,
                minimumOverlapRatio,
                geometry(routeRow[3]),
                geometry(routeRow[4]),
                signals,
                sampleItem.review()
        );
    }

    private List<RouteReviewDetailDto.RouteSignalDto> findSignals(UUID rideId) {
        Query signalQuery = entityManager.createNativeQuery("""
                SELECT event.id,
                       event.segment_id,
                       event.event_type,
                       segment.street_name,
                       ST_AsGeoJSON(segment.geometry)
                FROM segment_events event
                JOIN street_segments segment ON segment.id = event.segment_id
                WHERE event.ride_id = :rideId
                  AND segment.geometry IS NOT NULL
                ORDER BY event.event_type, event.event_timestamp, event.id
                """).setParameter("rideId", rideId);
        @SuppressWarnings("unchecked")
        List<Object[]> signalRows = signalQuery.getResultList();
        return signalRows.stream()
                .map(row -> new RouteReviewDetailDto.RouteSignalDto(
                        uuid(row[0]),
                        longValue(row[1]),
                        string(row[2]),
                        string(row[3]),
                        geometry(row[4])
                ))
                .toList();
    }

    @Transactional
    public RouteReviewDto saveReview(UUID rideId, RouteReviewRequestDto request) {
        requireSampleItem(rideId);
        validate(request);

        Ride ride = rideRepository.findById(rideId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ride not found"));
        RouteComparisonReview review = reviewRepository.findByRideId(rideId)
                .orElseGet(RouteComparisonReview::new);
        review.setRide(ride);
        review.setManualClassification(request.manualClassification());
        review.setIssueCodes(copyIssues(request.issueCodes()));
        review.setNotes(normalizeNotes(request.notes()));
        review.setReviewedAt(Instant.now());
        return RouteReviewDto.from(reviewRepository.save(review));
    }

    @Transactional(readOnly = true)
    public String exportSampleCsv() {
        RouteReviewSampleDto sample = getSample();
        StringWriter output = new StringWriter();
        try (CSVWriter csvWriter = new CSVWriter(output)) {
            csvWriter.writeNext(CSV_HEADER);
            for (RouteReviewSampleItemDto item : sample.items()) {
                RouteReviewDetailDto detail = buildDetail(item, false);
                RouteReviewDto review = detail.review();
                csvWriter.writeNext(new String[]{
                        value(item.rideId()),
                        value(item.sampleOrder()),
                        value(item.classSampleRank()),
                        value(item.automatedClassification()),
                        value(item.startTimestamp()),
                        value(item.rideIntent()),
                        value(item.bikeType()),
                        value(item.actualDistanceMeters()),
                        value(item.shortestPathDistanceMeters()),
                        value(item.absoluteExcessDistanceMeters()),
                        value(item.relativeDetourRatio()),
                        value(item.overlapRatio()),
                        value(detail.detourThresholdRatio()),
                        value(detail.maximumEquivalentExcessDistanceMeters()),
                        value(detail.minimumOverlapRatio()),
                        value(detail.medianGpsAccuracyMeters()),
                        value(detail.gpsPointCount()),
                        json(detail.observedRoute()),
                        json(detail.shortestRoute()),
                        review != null ? value(review.manualClassification()) : "",
                        review != null ? review.issueCodes().stream()
                                .map(Enum::name).collect(Collectors.joining(";")) : "",
                        review != null ? value(review.notes()) : "",
                        review != null ? value(review.reviewedAt()) : ""
                });
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not create route review CSV", exception);
        }
        return output.toString();
    }

    private RouteReviewSampleItemDto requireSampleItem(UUID rideId) {
        return getSample().items().stream()
                .filter(item -> item.rideId().equals(rideId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Ride is not part of the route review sample"));
    }

    private void validate(RouteReviewRequestDto request) {
        if (request == null || request.manualClassification() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "manualClassification is required");
        }
        String notes = normalizeNotes(request.notes());
        if (notes != null && notes.length() > MAX_NOTES_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "notes must not exceed 1000 characters");
        }
        Set<RouteComparisonReviewIssue> issues = request.issueCodes() != null
                ? request.issueCodes()
                : Set.of();
        if (issues.contains(RouteComparisonReviewIssue.OTHER) && notes == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "notes are required when OTHER is selected");
        }
    }

    private Set<RouteComparisonReviewIssue> copyIssues(Set<RouteComparisonReviewIssue> issues) {
        return issues == null || issues.isEmpty()
                ? EnumSet.noneOf(RouteComparisonReviewIssue.class)
                : EnumSet.copyOf(issues);
    }

    private String normalizeNotes(String notes) {
        if (notes == null || notes.isBlank()) {
            return null;
        }
        return notes.trim();
    }

    private RouteReviewDetailDto.GeoJsonLineString geometry(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value.toString(), RouteReviewDetailDto.GeoJsonLineString.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not parse route GeoJSON", exception);
        }
    }

    private String json(Object value) {
        if (value == null) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize route GeoJSON", exception);
        }
    }

    private UUID uuid(Object value) {
        return value instanceof UUID id ? id : UUID.fromString(value.toString());
    }

    private String string(Object value) {
        return value != null ? value.toString() : null;
    }

    private Double decimal(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private int integer(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String value(Object value) {
        return value != null ? value.toString() : "";
    }
}

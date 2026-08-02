package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.RideIntent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEnrichmentFilter;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEventType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.EnrichmentStatus;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficCondition;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentEventRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.AnalysisDimension;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.AnalyticsContextDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.CorridorRankingDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.DimensionBucketDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.InfrastructureSignalsDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.ProcessingSummaryDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.PipelineStatusDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ApiAnalyticsService {

    private final RideRepository rideRepository;
    private final StreetSegmentRepository streetSegmentRepository;
    private final SegmentEventRepository segmentEventRepository;
    private final EntityManager entityManager;

    public ApiAnalyticsService(RideRepository rideRepository,
                               StreetSegmentRepository streetSegmentRepository,
                               SegmentEventRepository segmentEventRepository,
                               EntityManager entityManager) {
        this.rideRepository = rideRepository;
        this.streetSegmentRepository = streetSegmentRepository;
        this.segmentEventRepository = segmentEventRepository;
        this.entityManager = entityManager;
    }

    public ProcessingSummaryDto getProcessingSummary() {
        Map<String, Long> rideStatusCounts = new LinkedHashMap<>();
        for (Status status : Status.values()) {
            rideStatusCounts.put(status.name(), rideRepository.countByStatus(status));
        }

        Map<String, Long> eventTypeCounts = new LinkedHashMap<>();
        for (SegmentEventType eventType : SegmentEventType.values()) {
            eventTypeCounts.put(eventType.name(), segmentEventRepository.countByEventType(eventType));
        }

        return new ProcessingSummaryDto(
                rideRepository.count(),
                rideStatusCounts,
                streetSegmentRepository.count(),
                streetSegmentRepository.countObservedSegments(),
                segmentEventRepository.count(),
                segmentEventRepository.findEarliestEventTimestamp(),
                segmentEventRepository.findLatestEventTimestamp(),
                eventTypeCounts,
                segmentEventRepository.countByWeatherEnriched(true),
                segmentEventRepository.countByOhsomeEnriched(true),
                segmentEventRepository.countByBerlinOpenDataEnriched(true),
                segmentEventRepository.countByTrafficEnriched(true),
                segmentEventRepository.countTrafficMeasuredEvents()
        );
    }

    public PipelineStatusDto getPipelineStatus() {
        return new PipelineStatusDto(
                rideStatusCounts(),
                segmentEventRepository.count(),
                enrichmentStatusCounts(segmentEventRepository::countByWeatherProcessingStatus),
                enrichmentStatusCounts(segmentEventRepository::countByBerlinOpenDataProcessingStatus),
                enrichmentStatusCounts(segmentEventRepository::countByOhsomeProcessingStatus),
                enrichmentStatusCounts(segmentEventRepository::countByTrafficProcessingStatus)
        );
    }

    private Map<String, Long> rideStatusCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Status status : Status.values()) {
            counts.put(status.name(), rideRepository.countByStatus(status));
        }
        return counts;
    }

    private Map<String, Long> enrichmentStatusCounts(java.util.function.Function<EnrichmentStatus, Long> counter) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (EnrichmentStatus status : EnrichmentStatus.values()) {
            counts.put(status.name(), counter.apply(status));
        }
        return counts;
    }

    public List<DimensionBucketDto> getEventDistribution(AnalysisDimension dimension,
                                                         Long from,
                                                         Long to,
                                                         SegmentEventType eventType,
                                                         RideIntent rideIntent,
                                                         TrafficCondition trafficCondition,
                                                         List<SegmentEnrichmentFilter> enrichmentFilters,
                                                         int limit) {
        String dimensionExpression = dimensionExpression(dimension);
        StringBuilder jpql = new StringBuilder("""
                SELECT %s,
                       COUNT(e),
                       SUM(CASE WHEN e.eventType = :avoidanceType THEN 1 ELSE 0 END),
                       SUM(CASE WHEN e.eventType = :preferenceType THEN 1 ELSE 0 END),
                       AVG(e.temperature2m),
                       AVG(e.precipitation),
                       AVG(e.windSpeed10m),
                       AVG(e.relativeWindAngleDegrees),
                       AVG(s.gradientPercent),
                       AVG(e.trafficVolumeKfz),
                       AVG(e.trafficSpeedKfz)
                FROM SegmentEvent e
                JOIN e.segment s
                WHERE 1 = 1
                """.formatted(dimensionExpression));

        EnrichmentFlags flags = EnrichmentFlags.of(enrichmentFilters);
        appendFilters(jpql, from, to, eventType, rideIntent, trafficCondition, flags);
        jpql.append(" GROUP BY ").append(dimensionExpression);
        jpql.append(" ORDER BY COUNT(e) DESC");

        Query query = entityManager.createQuery(jpql.toString())
                .setParameter("avoidanceType", SegmentEventType.AVOIDANCE)
                .setParameter("preferenceType", SegmentEventType.PREFERENCE)
                .setMaxResults(Math.max(1, Math.min(limit, 200)));
        bindFilters(query, from, to, eventType, rideIntent, trafficCondition);

        return query.getResultList().stream()
                .map(row -> toDimensionBucket(dimension, (Object[]) row))
                .toList();
    }

    public AnalyticsContextDto getAnalyticsContext(Long from, Long to, RideIntent rideIntent) {
        validateRange(from, to);
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                SELECT COUNT(DISTINCT e.ride_id),
                       COUNT(*),
                       COUNT(*) FILTER (WHERE e.event_type = 'AVOIDANCE'),
                       COUNT(*) FILTER (WHERE e.event_type = 'PREFERENCE'),
                       MIN(e.event_timestamp),
                       MAX(e.event_timestamp)
                FROM segment_events e
                WHERE e.event_timestamp >= :from AND e.event_timestamp <= :to
                  AND (:rideIntent = '' OR e.ride_intent = :rideIntent)
                """)
                .setParameter("from", lowerBound(from))
                .setParameter("to", upperBound(to))
                .setParameter("rideIntent", enumName(rideIntent))
                .getSingleResult();

        return new AnalyticsContextDto(
                asLong(row[0]), asLong(row[1]), asLong(row[2]), asLong(row[3]),
                asNullableLong(row[4]), asNullableLong(row[5]));
    }

    public List<CorridorRankingDto> getCorridorRanking(SegmentEventType rank,
                                                        int minRideCount,
                                                        int limit,
                                                        Long from,
                                                        Long to,
                                                        RideIntent rideIntent) {
        validateRange(from, to);
        String rankName = rank != null ? rank.name() : SegmentEventType.AVOIDANCE.name();
        List<Object[]> rows = streetSegmentRepository.findCorridorRankings(
                rankName,
                Math.max(1, minRideCount),
                Math.max(1, Math.min(limit, 50)),
                lowerBound(from),
                upperBound(to),
                enumName(rideIntent));

        return rows.stream().map(row -> new CorridorRankingDto(
                stringValue(row[0]),
                asLong(row[1]), asLong(row[2]), asLong(row[3]), asLong(row[4]),
                asLong(row[5]), asLong(row[11]),
                asDouble(row[6]), asDouble(row[7]), asDouble(row[8]), asDouble(row[9]),
                asNullableLong(row[10]), commaSeparatedLongs(row[12]))).toList();
    }

    public InfrastructureSignalsDto getInfrastructureSignals(AnalysisDimension dimension,
                                                               int minRideCount,
                                                               int limit,
                                                               Long from,
                                                               Long to,
                                                               RideIntent rideIntent) {
        validateRange(from, to);
        String attribute = infrastructureAttributeExpression(dimension);
        String baseFilter = """
                e.event_timestamp >= :from AND e.event_timestamp <= :to
                AND (:rideIntent = '' OR e.ride_intent = :rideIntent)
                """;
        String knownFilter = """
                e.ohsome_enriched = true
                AND %s IS NOT NULL
                AND TRIM(CAST(%s AS text)) <> ''
                AND UPPER(TRIM(CAST(%s AS text))) <> 'UNKNOWN'
                """.formatted(attribute, attribute, attribute);

        Query totalsQuery = entityManager.createNativeQuery("""
                WITH filtered AS (
                    SELECT e.ride_id, e.event_type,
                           CASE WHEN %s THEN LOWER(TRIM(CAST(%s AS text))) END AS attribute_value
                    FROM segment_events e
                    WHERE %s
                ),
                known AS (
                    SELECT * FROM filtered WHERE attribute_value IS NOT NULL
                ),
                baseline_signals AS (
                    SELECT DISTINCT ride_id, event_type FROM known
                )
                SELECT (SELECT COUNT(*) FROM filtered),
                       (SELECT COUNT(*) FROM known),
                       COUNT(*) FILTER (WHERE event_type = 'AVOIDANCE'),
                       COUNT(*) FILTER (WHERE event_type = 'PREFERENCE')
                FROM baseline_signals
                """.formatted(knownFilter, attribute, baseFilter));
        bindAnalyticsScope(totalsQuery, from, to, rideIntent);
        Object[] totals = (Object[]) totalsQuery.getSingleResult();
        long matchingEvents = asLong(totals[0]);
        long knownEvents = asLong(totals[1]);
        long baselineAvoidance = asLong(totals[2]);
        long baselinePreference = asLong(totals[3]);
        Double baselineShare = share(baselineAvoidance, baselineAvoidance + baselinePreference);

        Query bucketsQuery = entityManager.createNativeQuery("""
                WITH known_signals AS (
                    SELECT DISTINCT e.ride_id,
                           e.event_type,
                           LOWER(TRIM(CAST(%s AS text))) AS attribute_value
                    FROM segment_events e
                    WHERE %s AND %s
                )
                SELECT attribute_value,
                       COUNT(*) FILTER (WHERE event_type = 'AVOIDANCE'),
                       COUNT(*) FILTER (WHERE event_type = 'PREFERENCE')
                FROM known_signals
                GROUP BY attribute_value
                HAVING COUNT(*) >= :minRideCount
                ORDER BY (COUNT(*) FILTER (WHERE event_type = 'AVOIDANCE'))::double precision
                         / NULLIF(COUNT(*), 0) DESC,
                         COUNT(*) DESC,
                         attribute_value
                LIMIT :limit
                """.formatted(attribute, baseFilter, knownFilter));
        bindAnalyticsScope(bucketsQuery, from, to, rideIntent);
        bucketsQuery.setParameter("minRideCount", Math.max(1, minRideCount));
        bucketsQuery.setParameter("limit", Math.max(1, Math.min(limit, 50)));

        @SuppressWarnings("unchecked")
        List<Object[]> bucketRows = bucketsQuery.getResultList();
        List<InfrastructureSignalsDto.InfrastructureSignalBucketDto> buckets = new ArrayList<>();
        for (Object[] row : bucketRows) {
            long avoidance = asLong(row[1]);
            long preference = asLong(row[2]);
            long total = avoidance + preference;
            Double avoidanceShare = share(avoidance, total);
            Double difference = avoidanceShare != null && baselineShare != null
                    ? (avoidanceShare - baselineShare) * 100.0
                    : null;
            buckets.add(new InfrastructureSignalsDto.InfrastructureSignalBucketDto(
                    stringValue(row[0]), avoidance, preference, total, avoidanceShare, difference));
        }
        buckets.sort(Comparator
                .comparing(InfrastructureSignalsDto.InfrastructureSignalBucketDto::percentagePointDifference,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(InfrastructureSignalsDto.InfrastructureSignalBucketDto::totalRideSignals,
                        Comparator.reverseOrder()));

        return new InfrastructureSignalsDto(
                dimension,
                matchingEvents,
                knownEvents,
                share(knownEvents, matchingEvents),
                baselineShare,
                buckets);
    }

    private String infrastructureAttributeExpression(AnalysisDimension dimension) {
        return switch (dimension) {
            case SURFACE -> "e.surface";
            case SMOOTHNESS -> "e.smoothness";
            case CYCLEWAY_TYPE -> "e.cycleway_type";
            case HIGHWAY -> "e.highway";
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Infrastructure dimension must be SURFACE, SMOOTHNESS, CYCLEWAY_TYPE, or HIGHWAY");
        };
    }

    private void bindAnalyticsScope(Query query, Long from, Long to, RideIntent rideIntent) {
        query.setParameter("from", lowerBound(from));
        query.setParameter("to", upperBound(to));
        query.setParameter("rideIntent", enumName(rideIntent));
    }

    private void validateRange(Long from, Long to) {
        if (from != null && to != null && from > to) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before or equal to to");
        }
    }

    private long lowerBound(Long from) {
        return from != null ? from : 0L;
    }

    private long upperBound(Long to) {
        return to != null ? to : Long.MAX_VALUE;
    }

    private String enumName(Enum<?> value) {
        return value != null ? value.name() : "";
    }

    private List<Long> commaSeparatedLongs(Object value) {
        if (value == null || value.toString().isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.toString().split(","))
                .map(Long::valueOf)
                .toList();
    }

    private void appendFilters(StringBuilder jpql, Long from, Long to, SegmentEventType eventType,
                               RideIntent rideIntent, TrafficCondition trafficCondition, EnrichmentFlags flags) {
        if (from != null) {
            jpql.append(" AND e.eventTimestamp >= :from");
        }
        if (to != null) {
            jpql.append(" AND e.eventTimestamp <= :to");
        }
        if (eventType != null) {
            jpql.append(" AND e.eventType = :eventType");
        }
        if (rideIntent != null) {
            jpql.append(" AND e.rideIntent = :rideIntent");
        }
        if (trafficCondition != null) {
            jpql.append(" AND e.trafficCondition = :trafficCondition");
        }
        if (flags.weather()) {
            jpql.append(" AND e.weatherEnriched = true");
        }
        if (flags.ohsome()) {
            jpql.append(" AND e.ohsomeEnriched = true");
        }
        if (flags.traffic()) {
            jpql.append(" AND e.trafficEnriched = true");
        }
        if (flags.measured()) {
            jpql.append(" AND e.trafficEnrichmentStatus = berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficEnrichmentStatus.ENRICHED");
        }
    }

    private void bindFilters(Query query, Long from, Long to, SegmentEventType eventType,
                             RideIntent rideIntent, TrafficCondition trafficCondition) {
        if (from != null) {
            query.setParameter("from", from);
        }
        if (to != null) {
            query.setParameter("to", to);
        }
        if (eventType != null) {
            query.setParameter("eventType", eventType);
        }
        if (rideIntent != null) {
            query.setParameter("rideIntent", rideIntent);
        }
        if (trafficCondition != null) {
            query.setParameter("trafficCondition", trafficCondition);
        }
    }

    record EnrichmentFlags(boolean weather, boolean ohsome, boolean traffic, boolean measured) {
        static EnrichmentFlags of(List<SegmentEnrichmentFilter> filters) {
            return new EnrichmentFlags(
                    filters != null && filters.contains(SegmentEnrichmentFilter.WEATHER_ENRICHED),
                    filters != null && filters.contains(SegmentEnrichmentFilter.OHSOME_ENRICHED),
                    filters != null && filters.contains(SegmentEnrichmentFilter.TRAFFIC_ENRICHED),
                    filters != null && filters.contains(SegmentEnrichmentFilter.TRAFFIC_MEASURED));
        }
    }

    private String dimensionExpression(AnalysisDimension dimension) {
        return switch (dimension) {
            case EVENT_TYPE -> "e.eventType";
            case HOUR_OF_DAY -> "e.hourOfDay";
            case DAY_OF_WEEK -> "e.dayOfWeek";
            case RIDE_INTENT -> "e.rideIntent";
            case WIND_EXPOSURE -> "e.windExposure";
            case CYCLEWAY_TYPE -> "e.cyclewayType";
            case CYCLEWAY_LOCATION -> "e.cyclewayLocation";
            case HIGHWAY -> "e.highway";
            case SURFACE -> "e.surface";
            case SMOOTHNESS -> "e.smoothness";
            case LIT -> "e.lit";
            case WEATHER_CODE -> "e.weatherCode";
            case TRAFFIC_CONDITION -> "e.trafficCondition";
            case PRECIPITATION_BUCKET -> """
                    CASE
                        WHEN e.precipitation IS NULL THEN 'UNKNOWN'
                        WHEN e.precipitation = 0 THEN '0 mm'
                        WHEN e.precipitation < 1 THEN '< 1 mm'
                        WHEN e.precipitation < 5 THEN '1-5 mm'
                        ELSE '>= 5 mm'
                    END
                    """;
            case TEMPERATURE_BUCKET -> """
                    CASE
                        WHEN e.temperature2m IS NULL THEN 'UNKNOWN'
                        WHEN e.temperature2m < 0 THEN '< 0 C'
                        WHEN e.temperature2m < 10 THEN '0-10 C'
                        WHEN e.temperature2m < 20 THEN '10-20 C'
                        WHEN e.temperature2m < 30 THEN '20-30 C'
                        ELSE '>= 30 C'
                    END
                    """;
            case WIND_SPEED_BUCKET -> """
                    CASE
                        WHEN e.windSpeed10m IS NULL THEN 'UNKNOWN'
                        WHEN e.windSpeed10m < 10 THEN '< 10 km/h'
                        WHEN e.windSpeed10m < 25 THEN '10-25 km/h'
                        WHEN e.windSpeed10m < 40 THEN '25-40 km/h'
                        ELSE '>= 40 km/h'
                    END
                    """;
            case GRADIENT_BUCKET -> """
                    CASE
                        WHEN s.gradientPercent IS NULL THEN 'UNKNOWN'
                        WHEN s.gradientPercent < -3 THEN '< -3%'
                        WHEN s.gradientPercent < 0 THEN '-3-0%'
                        WHEN s.gradientPercent < 3 THEN '0-3%'
                        ELSE '>= 3%'
                    END
                    """;
            case TRAFFIC_VOLUME_BUCKET -> """
                    CASE
                        WHEN e.trafficVolumeKfz IS NULL THEN 'UNKNOWN'
                        WHEN e.trafficVolumeKfz < 150 THEN '< 150'
                        WHEN e.trafficVolumeKfz < 800 THEN '150-800'
                        ELSE '>= 800'
                    END
                    """;
            case TRAFFIC_SPEED_BUCKET -> """
                    CASE
                        WHEN e.trafficSpeedKfz IS NULL THEN 'UNKNOWN'
                        WHEN e.trafficSpeedKfz < 20 THEN '< 20 km/h'
                        WHEN e.trafficSpeedKfz < 30 THEN '20-30 km/h'
                        WHEN e.trafficSpeedKfz < 50 THEN '30-50 km/h'
                        ELSE '>= 50 km/h'
                    END
                    """;
        };
    }

    private DimensionBucketDto toDimensionBucket(AnalysisDimension dimension, Object[] row) {
        long total = asLong(row[1]);
        long avoidance = asLong(row[2]);
        long preference = asLong(row[3]);
        return new DimensionBucketDto(
                dimension.name(),
                formatDimensionValue(row[0]),
                total,
                avoidance,
                preference,
                share(avoidance, total),
                share(preference, total),
                asDouble(row[4]),
                asDouble(row[5]),
                asDouble(row[6]),
                asDouble(row[7]),
                asDouble(row[8]),
                asDouble(row[9]),
                asDouble(row[10])
        );
    }

    private String formatDimensionValue(Object value) {
        if (value == null) {
            return "UNKNOWN";
        }
        return value.toString();
    }

    private Double share(long count, long total) {
        return total > 0 ? (double) count / total : null;
    }

    private long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private Long asNullableLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private String stringValue(Object value) {
        return value != null ? value.toString() : "";
    }
}

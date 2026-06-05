package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEventType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentEventRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.AnalysisDimension;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.DimensionBucketDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.ProcessingSummaryDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.TimeBucket;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.TimeSeriesBucketDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class ApiAnalyticsService {

    private static final ZoneId BERLIN_ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter BUCKET_LABEL_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

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

    /**
     * Returns a backend state summary for progress dashboards and sanity checks before
     * loading larger map or analysis views.
     */
    public ProcessingSummaryDto getProcessingSummary() {
        return new ProcessingSummaryDto(
                rideRepository.count(),
                countRidesByStatus(),
                streetSegmentRepository.count(),
                streetSegmentRepository.countObservedSegments(),
                segmentEventRepository.count(),
                segmentEventRepository.findMinEventTimestamp(),
                segmentEventRepository.findMaxEventTimestamp(),
                countSegmentEventsByType(),
                segmentEventRepository.countByWeatherEnriched(true),
                segmentEventRepository.countByOhsomeEnriched(true),
                segmentEventRepository.countByBerlinOpenDataEnriched(true),
                segmentEventRepository.countByTrafficEnriched(true),
                segmentEventRepository.countByTrafficConditionIsNotNull()
        );
    }

    /**
     * Groups segment events by one dimension and returns event counts and
     * average contextual values.
     */
    public List<DimensionBucketDto> getEventDistribution(AnalysisDimension dimension,
                                                         Long from,
                                                         Long to,
                                                         SegmentEventType eventType,
                                                         int limit) {
        String dimensionExpression = dimensionExpression(dimension);
        StringBuilder jpql = new StringBuilder("""
                SELECT %s,
                       SUM(CASE WHEN e.eventType = :avoidanceType THEN 1 ELSE 0 END),
                       SUM(CASE WHEN e.eventType = :preferenceType THEN 1 ELSE 0 END),
                       COUNT(e),
                       AVG(e.temperature2m),
                       AVG(e.precipitation),
                       AVG(e.windSpeed10m),
                       AVG(e.relativeWindAngleDegrees),
                       AVG(s.gradientPercent),
                       AVG(e.trafficVolumeKfz),
                       AVG(e.trafficSpeedKfz)
                FROM SegmentEvent e
                JOIN e.segment s
                """.formatted(dimensionExpression));

        List<String> predicates = eventPredicates(from, to, eventType, "e.eventTimestamp", "e.eventType");
        if (!predicates.isEmpty()) {
            jpql.append(" WHERE ").append(String.join(" AND ", predicates));
        }
        jpql.append(" GROUP BY ").append(dimensionExpression);
        jpql.append(" ORDER BY COUNT(e) DESC");

        Query query = entityManager.createQuery(jpql.toString());
        applyEventParameters(query, from, to, eventType);
        query.setParameter("avoidanceType", SegmentEventType.AVOIDANCE);
        query.setParameter("preferenceType", SegmentEventType.PREFERENCE);
        query.setMaxResults(limitToRange(limit, 1, 200));

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        return rows.stream()
                .map(row -> toDimensionBucket(dimension, row))
                .toList();
    }

    /**
     * Aggregates event counts into day/week/month buckets.
     */
    public List<TimeSeriesBucketDto> getEventTimeSeries(TimeBucket bucket,
                                                        Long from,
                                                        Long to,
                                                        SegmentEventType eventType) {
        StringBuilder sql = new StringBuilder("""
                SELECT date_trunc(:bucket, to_timestamp(se.event_timestamp / 1000.0) AT TIME ZONE 'Europe/Berlin') AS bucket_start,
                       SUM(CASE WHEN se.event_type = 'AVOIDANCE' THEN 1 ELSE 0 END) AS avoidance_count,
                       SUM(CASE WHEN se.event_type = 'PREFERENCE' THEN 1 ELSE 0 END) AS preference_count,
                       COUNT(*) AS total_count
                FROM segment_events se
                """);

        List<String> predicates = new ArrayList<>();
        if (from != null) {
            predicates.add("se.event_timestamp >= :fromEpochMillis");
        }
        if (to != null) {
            predicates.add("se.event_timestamp <= :toEpochMillis");
        }
        if (eventType != null) {
            predicates.add("se.event_type = :eventType");
        }
        if (!predicates.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", predicates));
        }
        sql.append(" GROUP BY bucket_start ORDER BY bucket_start");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("bucket", bucket.getPostgresDateTruncValue());
        if (from != null) {
            query.setParameter("fromEpochMillis", from);
        }
        if (to != null) {
            query.setParameter("toEpochMillis", to);
        }
        if (eventType != null) {
            query.setParameter("eventType", eventType.name());
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        return rows.stream()
                .map(this::toTimeSeriesBucket)
                .toList();
    }

    private Map<String, Long> countRidesByStatus() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Status status : Status.values()) {
            counts.put(status.name(), rideRepository.countByStatus(status));
        }
        return counts;
    }

    private Map<String, Long> countSegmentEventsByType() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (SegmentEventType eventType : SegmentEventType.values()) {
            counts.put(eventType.name(), segmentEventRepository.countByEventType(eventType));
        }
        return counts;
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
            case PRECIPITATION_BUCKET -> """
                    CASE
                        WHEN e.precipitation IS NULL THEN 'UNKNOWN'
                        WHEN e.precipitation = 0 THEN 'DRY'
                        WHEN e.precipitation < 2 THEN 'LIGHT'
                        ELSE 'HEAVY'
                    END
                    """;
            case TEMPERATURE_BUCKET -> """
                    CASE
                        WHEN e.temperature2m IS NULL THEN 'UNKNOWN'
                        WHEN e.temperature2m < 0 THEN 'FREEZING'
                        WHEN e.temperature2m < 10 THEN 'COLD'
                        WHEN e.temperature2m < 20 THEN 'MILD'
                        WHEN e.temperature2m < 30 THEN 'WARM'
                        ELSE 'HOT'
                    END
                    """;
            case WIND_SPEED_BUCKET -> """
                    CASE
                        WHEN e.windSpeed10m IS NULL THEN 'UNKNOWN'
                        WHEN e.windSpeed10m < 12 THEN 'LIGHT'
                        WHEN e.windSpeed10m < 29 THEN 'MODERATE'
                        ELSE 'STRONG'
                    END
                    """;
            case GRADIENT_BUCKET -> """
                    CASE
                        WHEN s.gradientPercent IS NULL THEN 'UNKNOWN'
                        WHEN s.gradientPercent < -3 THEN 'DOWNHILL_STEEP'
                        WHEN s.gradientPercent < -1 THEN 'DOWNHILL'
                        WHEN s.gradientPercent <= 1 THEN 'FLAT'
                        WHEN s.gradientPercent <= 3 THEN 'UPHILL'
                        ELSE 'UPHILL_STEEP'
                    END
                    """;
            case TRAFFIC_CONDITION -> "e.trafficCondition";
            case TRAFFIC_VOLUME_BUCKET -> """
                    CASE
                        WHEN e.trafficVolumeKfz IS NULL THEN 'UNKNOWN'
                        WHEN e.trafficVolumeKfz < 150 THEN 'LIGHT'
                        WHEN e.trafficVolumeKfz < 800 THEN 'MODERATE'
                        ELSE 'HEAVY'
                    END
                    """;
            case TRAFFIC_SPEED_BUCKET -> """
                    CASE
                        WHEN e.trafficSpeedKfz IS NULL THEN 'UNKNOWN'
                        WHEN e.trafficSpeedKfz < 20 THEN 'CONGESTED'
                        WHEN e.trafficSpeedKfz < 30 THEN 'SLOW'
                        WHEN e.trafficSpeedKfz < 50 THEN 'URBAN'
                        ELSE 'FAST'
                    END
                    """;
        };
    }

    private List<String> eventPredicates(Long from,
                                         Long to,
                                         SegmentEventType eventType,
                                         String timestampExpression,
                                         String eventTypeExpression) {
        List<String> predicates = new ArrayList<>();
        if (from != null) {
            predicates.add(timestampExpression + " >= :fromEpochMillis");
        }
        if (to != null) {
            predicates.add(timestampExpression + " <= :toEpochMillis");
        }
        if (eventType != null) {
            predicates.add(eventTypeExpression + " = :eventType");
        }
        return predicates;
    }

    private void applyEventParameters(Query query, Long from, Long to, SegmentEventType eventType) {
        if (from != null) {
            query.setParameter("fromEpochMillis", from);
        }
        if (to != null) {
            query.setParameter("toEpochMillis", to);
        }
        if (eventType != null) {
            query.setParameter("eventType", eventType);
        }
    }

    private DimensionBucketDto toDimensionBucket(AnalysisDimension dimension, Object[] row) {
        long avoidanceCount = asLong(row[1]);
        long preferenceCount = asLong(row[2]);
        long totalCount = asLong(row[3]);

        return new DimensionBucketDto(
                dimension.name(),
                valueToString(row[0]),
                totalCount,
                avoidanceCount,
                preferenceCount,
                share(avoidanceCount, totalCount),
                share(preferenceCount, totalCount),
                asDouble(row[4]),
                asDouble(row[5]),
                asDouble(row[6]),
                asDouble(row[7]),
                asDouble(row[8]),
                asDouble(row[9]),
                asDouble(row[10])
        );
    }

    private TimeSeriesBucketDto toTimeSeriesBucket(Object[] row) {
        long avoidanceCount = asLong(row[1]);
        long preferenceCount = asLong(row[2]);
        long totalCount = asLong(row[3]);
        Long bucketStart = toEpochMillis(row[0]);

        return new TimeSeriesBucketDto(
                bucketStart,
                bucketStart != null ? BUCKET_LABEL_FORMATTER.format(Instant.ofEpochMilli(bucketStart).atZone(BERLIN_ZONE)) : null,
                totalCount,
                avoidanceCount,
                preferenceCount,
                share(avoidanceCount, totalCount),
                share(preferenceCount, totalCount)
        );
    }

    private String valueToString(Object value) {
        if (value == null) {
            return "UNKNOWN";
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return value.toString();
    }

    private long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private Double share(long numerator, long denominator) {
        return denominator > 0 ? (double) numerator / denominator : null;
    }

    private int limitToRange(int limit, int min, int max) {
        return Math.max(min, Math.min(limit, max));
    }

    private Long toEpochMillis(Object value) {
        return switch (value) {
            case Timestamp timestamp -> timestamp.toLocalDateTime().atZone(BERLIN_ZONE).toInstant().toEpochMilli();
            case LocalDateTime localDateTime -> localDateTime.atZone(BERLIN_ZONE).toInstant().toEpochMilli();
            case java.util.Date date -> date.toInstant().toEpochMilli();
            case null, default -> null;
        };
    }
}

package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEventType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.EnrichmentStatus;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentEventRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.AnalysisDimension;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.DimensionBucketDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.ProcessingSummaryDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.PipelineStatusDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.TimeBucket;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.TimeSeriesBucketDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Service
public class ApiAnalyticsService {

    private static final ZoneId BERLIN_ZONE = ZoneId.of("Europe/Berlin");

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

        appendFilters(jpql, from, to, eventType);
        jpql.append(" GROUP BY ").append(dimensionExpression);
        jpql.append(" ORDER BY COUNT(e) DESC");

        Query query = entityManager.createQuery(jpql.toString())
                .setParameter("avoidanceType", SegmentEventType.AVOIDANCE)
                .setParameter("preferenceType", SegmentEventType.PREFERENCE)
                .setMaxResults(Math.max(1, Math.min(limit, 200)));
        bindFilters(query, from, to, eventType);

        return query.getResultList().stream()
                .map(row -> toDimensionBucket(dimension, (Object[]) row))
                .toList();
    }

    public List<TimeSeriesBucketDto> getEventTimeSeries(TimeBucket bucket,
                                                        Long from,
                                                        Long to,
                                                        SegmentEventType eventType) {
        List<Object[]> rows = segmentEventRepository.findEventTimelineRows(eventType, from, to);
        Map<Long, MutableTimeBucket> buckets = new TreeMap<>();

        for (Object[] row : rows) {
            Long timestamp = row[0] instanceof Number number ? number.longValue() : null;
            if (timestamp == null) {
                continue;
            }

            SegmentEventType rowEventType = row[1] instanceof SegmentEventType type ? type : null;
            long bucketStart = bucketStart(timestamp, bucket);
            MutableTimeBucket mutable = buckets.computeIfAbsent(bucketStart, key -> new MutableTimeBucket());
            mutable.totalCount++;
            if (rowEventType == SegmentEventType.AVOIDANCE) {
                mutable.avoidanceCount++;
            } else if (rowEventType == SegmentEventType.PREFERENCE) {
                mutable.preferenceCount++;
            }
        }

        return buckets.entrySet().stream()
                .map(entry -> {
                    MutableTimeBucket value = entry.getValue();
                    return new TimeSeriesBucketDto(
                            entry.getKey(),
                            label(entry.getKey(), bucket),
                            value.totalCount,
                            value.avoidanceCount,
                            value.preferenceCount,
                            share(value.avoidanceCount, value.totalCount),
                            share(value.preferenceCount, value.totalCount)
                    );
                })
                .toList();
    }

    private void appendFilters(StringBuilder jpql, Long from, Long to, SegmentEventType eventType) {
        if (from != null) {
            jpql.append(" AND e.eventTimestamp >= :from");
        }
        if (to != null) {
            jpql.append(" AND e.eventTimestamp <= :to");
        }
        if (eventType != null) {
            jpql.append(" AND e.eventType = :eventType");
        }
    }

    private void bindFilters(Query query, Long from, Long to, SegmentEventType eventType) {
        if (from != null) {
            query.setParameter("from", from);
        }
        if (to != null) {
            query.setParameter("to", to);
        }
        if (eventType != null) {
            query.setParameter("eventType", eventType);
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

    private long bucketStart(Long timestamp, TimeBucket bucket) {
        ZonedDateTime dateTime = Instant.ofEpochMilli(timestamp).atZone(BERLIN_ZONE);
        ZonedDateTime start = switch (bucket) {
            case DAY -> dateTime.toLocalDate().atStartOfDay(BERLIN_ZONE);
            case WEEK -> dateTime.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .toLocalDate()
                    .atStartOfDay(BERLIN_ZONE);
            case MONTH -> dateTime.withDayOfMonth(1).toLocalDate().atStartOfDay(BERLIN_ZONE);
        };
        return start.toInstant().toEpochMilli();
    }

    private String label(Long timestamp, TimeBucket bucket) {
        ZonedDateTime dateTime = Instant.ofEpochMilli(timestamp).atZone(BERLIN_ZONE);
        return switch (bucket) {
            case DAY -> dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE);
            case WEEK -> "Week of " + dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE);
            case MONTH -> dateTime.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH));
        };
    }

    private Double share(long count, long total) {
        return total > 0 ? (double) count / total : null;
    }

    private long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static class MutableTimeBucket {
        private long totalCount;
        private long avoidanceCount;
        private long preferenceCount;
    }
}

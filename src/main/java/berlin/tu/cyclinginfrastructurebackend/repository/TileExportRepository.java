package berlin.tu.cyclinginfrastructurebackend.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Streams street segments as newline-delimited GeoJSON features (GeoJSONSeq) for
 * vector tile generation with tippecanoe. The complete feature JSON is assembled
 * in PostGIS so no GeoJSON serialization happens in Java.
 * <p>
 * Besides the all-time aggregates, every feature carries per-year properties
 * ({@code eventCount_<year>}, {@code bucket_<year>}) for each calendar year that has
 * events, so the map can filter and color by year without a separate overlay.
 * Zero-event years are emitted as NULL and removed by jsonb_strip_nulls, so most
 * features only grow by the one or two years they actually have events in.
 */
@Repository
public class TileExportRepository {

    // events before this year are device-clock artifacts and not offered as a filter
    // (mirrors minSelectableEventYear in the frontend)
    private static final int MIN_EXPORT_YEAR = 2015;

    private final JdbcTemplate jdbcTemplate;

    public TileExportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcTemplate.setFetchSize(1000);
    }

    // readOnly transactions disable autocommit so the Postgres driver honors the
    // fetch size and streams rows instead of buffering the full result set
    @Transactional(readOnly = true)
    public long exportSegmentFeatures(Writer out) {
        return export(segmentFeaturesSql(exportYears()), out);
    }

    /** Calendar years (UTC) that carry events, clamped to the plausible range. */
    private List<Integer> exportYears() {
        int currentYear = Year.now(ZoneOffset.UTC).getValue();
        return jdbcTemplate.queryForList("""
                        SELECT DISTINCT EXTRACT(YEAR FROM to_timestamp(event_timestamp / 1000.0) AT TIME ZONE 'UTC')::int AS event_year
                        FROM segment_events
                        WHERE event_timestamp IS NOT NULL
                        ORDER BY event_year
                        """, Integer.class).stream()
                .filter(year -> year >= MIN_EXPORT_YEAR && year <= currentYear)
                .toList();
    }

    /**
     * The balance mirrors the frontend's eventBalance(): additive smoothing with
     * BALANCE_PRIOR_STRENGTH phantom neutral events in the denominator, so the score
     * grows with both the one-sidedness and the amount of evidence and approaches
     * (but never reaches) +-1. Unanimous boundaries: 1-2 events LIGHT, 3-7 moderate,
     * 8-44 STRONG, 45+ EXTREME. The bucket CASE mirrors eventSignalBucket().
     * traffic_measured means an attached detector measurement (status ENRICHED),
     * matching the segments API.
     */
    private static final int BALANCE_PRIOR_STRENGTH = 5;

    private static String balanceExpression(String avoidance, String preference) {
        String total = "(" + avoidance + " + " + preference + ")";
        return """
                CASE
                    WHEN %s > 0
                        THEN ((%s - %s)::double precision / (%s + %d))
                    ELSE 0
                END""".formatted(total, preference, avoidance, total, BALANCE_PRIOR_STRENGTH);
    }

    private static String bucketCase(String eventCount, String balance) {
        return """
                CASE
                    WHEN %s = 0 THEN 'NO_EVENTS'
                    WHEN %s <= -0.9 THEN 'AVOIDANCE_EXTREME'
                    WHEN %s <= -0.6 THEN 'AVOIDANCE_STRONG'
                    WHEN %s <= -0.3 THEN 'AVOIDANCE'
                    WHEN %s <= -0.1 THEN 'AVOIDANCE_LIGHT'
                    WHEN %s >= 0.9 THEN 'PREFERENCE_EXTREME'
                    WHEN %s >= 0.6 THEN 'PREFERENCE_STRONG'
                    WHEN %s >= 0.3 THEN 'PREFERENCE'
                    WHEN %s >= 0.1 THEN 'PREFERENCE_LIGHT'
                    ELSE 'BASELINE'
                END""".formatted(eventCount, balance, balance, balance, balance,
                balance, balance, balance, balance);
    }

    private static long yearStartMillis(int year) {
        return LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private static String yearEnrichmentCounts(List<Integer> years) {
        return years.stream().map(year -> """
                ,COUNT(*) FILTER (WHERE event_type = 'AVOIDANCE'
                    AND event_timestamp >= %d AND event_timestamp < %d)  AS avoidance_%d
                ,COUNT(*) FILTER (WHERE event_type = 'PREFERENCE'
                    AND event_timestamp >= %d AND event_timestamp < %d)  AS preference_%d
                """.formatted(
                yearStartMillis(year), yearStartMillis(year + 1), year,
                yearStartMillis(year), yearStartMillis(year + 1), year))
                .collect(Collectors.joining());
    }

    private static String yearSegColumns(List<Integer> years) {
        return years.stream().map(year -> """
                ,(COALESCE(e.avoidance_%d, 0) + COALESCE(e.preference_%d, 0)) AS event_count_%d
                ,%s AS balance_%d
                """.formatted(
                year, year, year,
                balanceExpression("COALESCE(e.avoidance_" + year + ", 0)", "COALESCE(e.preference_" + year + ", 0)"),
                year))
                .collect(Collectors.joining());
    }

    private static String yearProperties(List<Integer> years) {
        return years.stream().map(year -> """
                ,'eventCount_%d', NULLIF(event_count_%d, 0)
                ,'bucket_%d', CASE WHEN event_count_%d > 0 THEN %s END
                """.formatted(
                year, year,
                year, year, bucketCase("event_count_" + year, "balance_" + year)))
                .collect(Collectors.joining());
    }

    private static String segmentFeaturesSql(List<Integer> years) {
        return """
                WITH enrichment AS (
                    SELECT segment_id,
                           COUNT(*) FILTER (WHERE traffic_enriched)                          AS traffic_enriched_count,
                           COUNT(*) FILTER (WHERE weather_enriched)                          AS weather_enriched_count,
                           COUNT(*) FILTER (WHERE ohsome_enriched)                           AS ohsome_enriched_count,
                           COUNT(*) FILTER (WHERE traffic_enrichment_status = 'ENRICHED')    AS traffic_measured_count
                """ + yearEnrichmentCounts(years) + """
                    FROM segment_events
                    GROUP BY segment_id
                ),
                seg AS (
                    SELECT s.id,
                           s.street_name,
                           s.geometry,
                           s.usage_count,
                           s.avoidance_count,
                           s.avoidance_ratio,
                           s.preference_count,
                           s.preference_ratio,
                           s.gradient_percent,
                           (s.usage_count + s.avoidance_count)                      AS obs,
                           (s.avoidance_count + s.preference_count)                 AS event_count,
                """ + balanceExpression("s.avoidance_count", "s.preference_count") + """
                           AS balance,
                           COALESCE(e.traffic_enriched_count, 0) AS traffic_enriched_count,
                           COALESCE(e.weather_enriched_count, 0) AS weather_enriched_count,
                           COALESCE(e.ohsome_enriched_count, 0)  AS ohsome_enriched_count,
                           COALESCE(e.traffic_measured_count, 0) AS traffic_measured_count
                """ + yearSegColumns(years) + """
                    FROM street_segments s
                    LEFT JOIN enrichment e ON e.segment_id = s.id
                    WHERE s.geometry IS NOT NULL
                      AND (s.usage_count + s.avoidance_count) >= 1
                )
                SELECT jsonb_build_object(
                    'type', 'Feature',
                    'geometry', ST_AsGeoJSON(geometry, 6)::jsonb,
                    'properties', jsonb_strip_nulls(jsonb_build_object(
                        'id', id,
                        'streetName', street_name,
                        'usageCount', usage_count,
                        'avoidanceCount', avoidance_count,
                        'preferenceCount', preference_count,
                        'avoidanceRatio', avoidance_ratio,
                        'preferenceRatio', preference_ratio,
                        'totalObservationCount', obs,
                        'gradientPercent', gradient_percent,
                        'eventCount', event_count,
                        'balance', balance,
                        'bucket', """ + bucketCase("event_count", "balance") + """
                        ,
                        'trafficEnrichedEventCount', traffic_enriched_count,
                        'weatherEnrichedEventCount', weather_enriched_count,
                        'ohsomeEnrichedEventCount', ohsome_enriched_count,
                        'trafficMeasuredEventCount', traffic_measured_count
                """ + yearProperties(years) + """
                    ))
                )::text AS feature
                FROM seg
                """;
    }

    private long export(String sql, Writer out) {
        AtomicLong count = new AtomicLong();
        jdbcTemplate.query(sql, resultSet -> {
            try {
                out.write(resultSet.getString("feature"));
                out.write('\n');
                count.incrementAndGet();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed writing tile export feature", e);
            }
        });
        return count.get();
    }
}

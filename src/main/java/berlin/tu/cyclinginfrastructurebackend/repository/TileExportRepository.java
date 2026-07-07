package berlin.tu.cyclinginfrastructurebackend.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streams street segments as newline-delimited GeoJSON features (GeoJSONSeq) for
 * vector tile generation with tippecanoe. The complete feature JSON is assembled
 * in PostGIS so no GeoJSON serialization happens in Java.
 */
@Repository
public class TileExportRepository {

    /**
     * Per-segment CTEs: enrichment event counts and derived balance/bucket inputs.
     * The balance CASE mirrors the frontend's eventBalance(): ratio-based when ratios are
     * present, otherwise count-based; the bucket CASE mirrors eventSignalBucket().
     */
    private static final String SEGMENT_CTE = """
            WITH enrichment AS (
                SELECT segment_id,
                       COUNT(*) FILTER (WHERE traffic_enriched)               AS traffic_enriched_count,
                       COUNT(*) FILTER (WHERE weather_enriched)               AS weather_enriched_count,
                       COUNT(*) FILTER (WHERE ohsome_enriched)                AS ohsome_enriched_count,
                       COUNT(*) FILTER (WHERE traffic_volume_kfz IS NOT NULL) AS traffic_measured_count
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
                       (s.usage_count + s.avoidance_count + s.preference_count) AS obs,
                       (s.avoidance_count + s.preference_count)                 AS event_count,
                       CASE
                           WHEN s.avoidance_ratio IS NOT NULL OR s.preference_ratio IS NOT NULL
                               THEN COALESCE(s.preference_ratio, 0) - COALESCE(s.avoidance_ratio, 0)
                           WHEN (s.avoidance_count + s.preference_count) > 0
                               THEN (s.preference_count - s.avoidance_count)::double precision
                                    / (s.preference_count + s.avoidance_count)
                           ELSE 0
                       END AS balance,
                       COALESCE(e.traffic_enriched_count, 0) AS traffic_enriched_count,
                       COALESCE(e.weather_enriched_count, 0) AS weather_enriched_count,
                       COALESCE(e.ohsome_enriched_count, 0)  AS ohsome_enriched_count,
                       COALESCE(e.traffic_measured_count, 0) AS traffic_measured_count
                FROM street_segments s
                LEFT JOIN enrichment e ON e.segment_id = s.id
                WHERE s.geometry IS NOT NULL
                  AND (s.usage_count + s.avoidance_count + s.preference_count) >= 1
            )
            """;

    private static final String BUCKET_CASE = """
            CASE
                WHEN event_count = 0 THEN 'NO_EVENTS'
                WHEN balance <= -0.6 THEN 'AVOIDANCE_STRONG'
                WHEN balance <= -0.3 THEN 'AVOIDANCE'
                WHEN balance <= -0.1 THEN 'AVOIDANCE_LIGHT'
                WHEN balance >= 0.6 THEN 'PREFERENCE_STRONG'
                WHEN balance >= 0.3 THEN 'PREFERENCE'
                WHEN balance >= 0.1 THEN 'PREFERENCE_LIGHT'
                ELSE 'BASELINE'
            END
            """;

    private static final String SEGMENT_FEATURES_SQL = SEGMENT_CTE + """
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
                    'bucket', """ + BUCKET_CASE + """
                    ,
                    'trafficEnrichedEventCount', traffic_enriched_count,
                    'weatherEnrichedEventCount', weather_enriched_count,
                    'ohsomeEnrichedEventCount', ohsome_enriched_count,
                    'trafficMeasuredEventCount', traffic_measured_count
                ))
            )::text AS feature
            FROM seg
            """;

    private final JdbcTemplate jdbcTemplate;

    public TileExportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.jdbcTemplate.setFetchSize(1000);
    }

    // readOnly transactions disable autocommit so the Postgres driver honors the
    // fetch size and streams rows instead of buffering the full result set
    @Transactional(readOnly = true)
    public long exportSegmentFeatures(Writer out) {
        return export(SEGMENT_FEATURES_SQL, out);
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

package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.EnrichmentStatus;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeBatchResult;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeWorkItem;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/** Set-based persistence for local Ohsome snapshot enrichment. */
@Repository
public class OhsomeEnrichmentBatchRepository {

    private static final String CLEAR_OSM_FIELDS = """
            ohsome_enriched = false,
            surface = NULL,
            smoothness = NULL,
            lit = NULL,
            highway = NULL,
            cycleway_type = NULL,
            cycleway_location = NULL,
            cycleway_surface = NULL,
            cycleway_width = NULL,
            bicycle_oneway = NULL
            """;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;

    public OhsomeEnrichmentBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    public boolean hasPendingWork() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM segment_events WHERE ohsome_processing_status = 'PENDING'
                )
                """, Boolean.class));
    }

    /** Finalizes events which cannot be represented by the configured snapshot dataset. */
    @Transactional
    public UnsupportedCounts finalizeUnsupported(long supportedFrom,
                                                  long supportedToExclusive,
                                                  double minLon,
                                                  double minLat,
                                                  double maxLon,
                                                  double maxLat) {
        int outsideTime = jdbcTemplate.update("""
                UPDATE segment_events
                SET ohsome_processing_status = 'DONE',
                    %s
                WHERE ohsome_processing_status = 'PENDING'
                  AND (event_timestamp IS NULL OR event_timestamp < ? OR event_timestamp >= ?)
                """.formatted(CLEAR_OSM_FIELDS), supportedFrom, supportedToExclusive);

        int outsideArea = jdbcTemplate.update("""
                UPDATE segment_events e
                SET ohsome_processing_status = 'DONE',
                    %s
                FROM street_segments s
                WHERE e.segment_id = s.id
                  AND e.ohsome_processing_status = 'PENDING'
                  AND e.event_timestamp >= ?
                  AND e.event_timestamp < ?
                  AND (s.geometry IS NULL OR NOT ST_Intersects(
                        s.geometry,
                        ST_MakeEnvelope(?, ?, ?, ?, 4326)
                  ))
                """.formatted(CLEAR_OSM_FIELDS),
                supportedFrom, supportedToExclusive, minLon, minLat, maxLon, maxLat);
        return new UnsupportedCounts(outsideTime, outsideArea);
    }

    /** Claims all pending events for up to {@code batchSize} distinct pairs in the earliest month. */
    @Transactional
    public List<OhsomeWorkItem> claimNextBatch(long supportedFrom,
                                               long supportedToExclusive,
                                               int batchSize) {
        Long earliestTimestamp = jdbcTemplate.queryForObject("""
                SELECT MIN(event_timestamp)
                FROM segment_events
                WHERE ohsome_processing_status = 'PENDING'
                  AND event_timestamp >= ?
                  AND event_timestamp < ?
                """, Long.class, supportedFrom, supportedToExclusive);
        if (earliestTimestamp == null) {
            return List.of();
        }

        YearMonth month = YearMonth.from(Instant.ofEpochMilli(earliestTimestamp).atZone(ZoneOffset.UTC));
        OhsomeWorkItem monthBounds = new OhsomeWorkItem(0, month);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("monthStart", monthBounds.monthStartMillis())
                .addValue("monthEnd", monthBounds.monthEndMillis())
                .addValue("batchSize", Math.max(1, batchSize));
        List<Long> segmentIds = namedJdbcTemplate.query("""
                WITH selected_segments AS MATERIALIZED (
                    SELECT DISTINCT segment_id
                    FROM segment_events
                    WHERE ohsome_processing_status = 'PENDING'
                      AND event_timestamp >= :monthStart
                      AND event_timestamp < :monthEnd
                    ORDER BY segment_id
                    LIMIT :batchSize
                ), claimed AS (
                    UPDATE segment_events e
                    SET ohsome_processing_status = 'PROCESSING'
                    FROM selected_segments s
                    WHERE e.ohsome_processing_status = 'PENDING'
                      AND e.segment_id = s.segment_id
                      AND e.event_timestamp >= :monthStart
                      AND e.event_timestamp < :monthEnd
                    RETURNING e.segment_id
                )
                SELECT DISTINCT segment_id
                FROM claimed
                ORDER BY segment_id
                """, parameters, (resultSet, rowNum) -> resultSet.getLong(1));

        return segmentIds.stream().map(id -> new OhsomeWorkItem(id, month)).toList();
    }

    /** Applies one prepared result to every claimed event for its segment/month pair. */
    @Transactional
    public int finalizeBatch(List<OhsomeBatchResult> results) {
        if (results.isEmpty()) {
            return 0;
        }

        jdbcTemplate.execute("""
                CREATE TEMP TABLE ohsome_batch_results (
                    segment_id bigint NOT NULL,
                    month_start bigint NOT NULL,
                    month_end bigint NOT NULL,
                    ohsome_enriched boolean NOT NULL,
                    processing_status varchar(20) NOT NULL,
                    surface varchar(255),
                    smoothness varchar(255),
                    lit varchar(255),
                    highway varchar(255),
                    cycleway_type varchar(255),
                    cycleway_location varchar(255),
                    cycleway_surface varchar(255),
                    cycleway_width double precision,
                    bicycle_oneway boolean
                ) ON COMMIT DROP
                """);

        jdbcTemplate.batchUpdate("""
                INSERT INTO ohsome_batch_results (
                    segment_id, month_start, month_end, ohsome_enriched, processing_status,
                    surface, smoothness, lit, highway, cycleway_type, cycleway_location,
                    cycleway_surface, cycleway_width, bicycle_oneway
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                OhsomeBatchResult result = results.get(index);
                statement.setLong(1, result.segmentId());
                statement.setLong(2, result.monthStartMillis());
                statement.setLong(3, result.monthEndMillis());
                statement.setBoolean(4, result.enriched());
                statement.setString(5, result.status().name());
                statement.setString(6, result.surface());
                statement.setString(7, result.smoothness());
                statement.setString(8, result.lit());
                statement.setString(9, result.highway());
                statement.setString(10, result.cyclewayType() == null ? null : result.cyclewayType().name());
                statement.setString(11, result.cyclewayLocation() == null ? null : result.cyclewayLocation().name());
                statement.setString(12, result.cyclewaySurface());
                if (result.cyclewayWidth() == null) {
                    statement.setNull(13, java.sql.Types.DOUBLE);
                } else {
                    statement.setDouble(13, result.cyclewayWidth());
                }
                if (result.bicycleOneway() == null) {
                    statement.setNull(14, java.sql.Types.BOOLEAN);
                } else {
                    statement.setBoolean(14, result.bicycleOneway());
                }
            }

            @Override
            public int getBatchSize() {
                return results.size();
            }
        });

        return jdbcTemplate.update("""
                UPDATE segment_events e
                SET ohsome_processing_status = r.processing_status,
                    ohsome_enriched = r.ohsome_enriched,
                    surface = r.surface,
                    smoothness = r.smoothness,
                    lit = r.lit,
                    highway = r.highway,
                    cycleway_type = r.cycleway_type,
                    cycleway_location = r.cycleway_location,
                    cycleway_surface = r.cycleway_surface,
                    cycleway_width = r.cycleway_width,
                    bicycle_oneway = r.bicycle_oneway
                FROM ohsome_batch_results r
                WHERE e.ohsome_processing_status = 'PROCESSING'
                  AND e.segment_id = r.segment_id
                  AND e.event_timestamp >= r.month_start
                  AND e.event_timestamp < r.month_end
                """);
    }

    @Transactional
    public int releaseBatch(List<OhsomeWorkItem> items) {
        if (items.isEmpty()) {
            return 0;
        }
        long monthStart = items.getFirst().monthStartMillis();
        long monthEnd = items.getFirst().monthEndMillis();
        List<Long> segmentIds = items.stream().map(OhsomeWorkItem::segmentId).toList();
        return namedJdbcTemplate.update("""
                UPDATE segment_events
                SET ohsome_processing_status = 'PENDING'
                WHERE ohsome_processing_status = 'PROCESSING'
                  AND segment_id IN (:segmentIds)
                  AND event_timestamp >= :monthStart
                  AND event_timestamp < :monthEnd
                """, new MapSqlParameterSource()
                .addValue("segmentIds", segmentIds)
                .addValue("monthStart", monthStart)
                .addValue("monthEnd", monthEnd));
    }

    public ProcessingCounts processingCounts() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE ohsome_processing_status = 'PENDING'),
                       COUNT(*) FILTER (WHERE ohsome_processing_status = 'PROCESSING'),
                       COUNT(*) FILTER (WHERE ohsome_processing_status = 'DONE' AND ohsome_enriched),
                       COUNT(*) FILTER (WHERE ohsome_processing_status = 'DONE' AND NOT ohsome_enriched),
                       COUNT(*) FILTER (WHERE ohsome_processing_status = 'ERROR')
                FROM segment_events
                """, (resultSet, rowNum) -> new ProcessingCounts(
                resultSet.getLong(1), resultSet.getLong(2), resultSet.getLong(3),
                resultSet.getLong(4), resultSet.getLong(5)));
    }

    public record UnsupportedCounts(long outsideTimeRange, long outsideArea) {}

    public record ProcessingCounts(long pending, long processing, long enriched, long noData, long errors) {}
}

package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeClaim;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeTile;
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
import java.util.Optional;

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

    /** Invalid source data cannot be assigned a tile/month; never discard other cities. */
    @Transactional
    public int markInvalidPendingEvents() {
        return jdbcTemplate.update("""
                UPDATE segment_events e
                SET ohsome_processing_status = 'ERROR',
                    %s
                WHERE e.ohsome_processing_status = 'PENDING'
                  AND (e.event_timestamp IS NULL OR NOT EXISTS (
                      SELECT 1 FROM street_segments s
                      WHERE s.id = e.segment_id
                        AND s.geometry IS NOT NULL
                        AND NOT ST_IsEmpty(s.geometry)
                        AND ST_IsValid(s.geometry)
                        AND ST_SRID(s.geometry) = 4326
                        AND ST_XMin(Box3D(s.geometry)) BETWEEN -180 AND 180
                        AND ST_XMax(Box3D(s.geometry)) BETWEEN -180 AND 180
                        AND ST_YMin(Box3D(s.geometry)) BETWEEN -90 AND 90
                        AND ST_YMax(Box3D(s.geometry)) BETWEEN -90 AND 90
                  ))
                """.formatted(CLEAR_OSM_FIELDS));
    }

    /** Claims one tile in the earliest pending UTC month, bounded by distinct segments. */
    @Transactional
    public Optional<OhsomeClaim> claimNextBatch(double gridSize, int batchSize) {
        Long earliestTimestamp = jdbcTemplate.queryForObject("""
                SELECT MIN(event_timestamp)
                FROM segment_events
                WHERE ohsome_processing_status = 'PENDING'
                """, Long.class);
        if (earliestTimestamp == null) {
            return Optional.empty();
        }
        YearMonth month = YearMonth.from(Instant.ofEpochMilli(earliestTimestamp).atZone(ZoneOffset.UTC));
        OhsomeWorkItem monthBounds = new OhsomeWorkItem(0, month);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("monthStart", monthBounds.monthStartMillis())
                .addValue("monthEnd", monthBounds.monthEndMillis())
                .addValue("gridSize", gridSize)
                .addValue("batchSize", Math.max(1, batchSize));
        List<ClaimedSegment> claimed = namedJdbcTemplate.query("""
                WITH pending_segments AS MATERIALIZED (
                    SELECT DISTINCT e.segment_id,
                           FLOOR(ST_X(p.midpoint)::numeric / CAST(:gridSize AS numeric))::integer AS tile_x,
                           FLOOR(ST_Y(p.midpoint)::numeric / CAST(:gridSize AS numeric))::integer AS tile_y
                    FROM segment_events e
                    JOIN street_segments s ON s.id = e.segment_id
                    CROSS JOIN LATERAL (SELECT ST_LineInterpolatePoint(s.geometry, 0.5) AS midpoint) p
                    WHERE e.ohsome_processing_status = 'PENDING'
                      AND e.event_timestamp >= :monthStart
                      AND e.event_timestamp < :monthEnd
                ), selected_tile AS (
                    SELECT tile_x, tile_y FROM pending_segments
                    ORDER BY tile_x, tile_y LIMIT 1
                ), selected_segments AS MATERIALIZED (
                    SELECT p.* FROM pending_segments p
                    JOIN selected_tile t USING (tile_x, tile_y)
                    ORDER BY segment_id LIMIT :batchSize
                ), claimed AS (
                    UPDATE segment_events e
                    SET ohsome_processing_status = 'PROCESSING'
                    FROM selected_segments s
                    WHERE e.ohsome_processing_status = 'PENDING'
                      AND e.segment_id = s.segment_id
                      AND e.event_timestamp >= :monthStart
                      AND e.event_timestamp < :monthEnd
                    RETURNING e.segment_id, s.tile_x, s.tile_y
                )
                SELECT DISTINCT segment_id, tile_x, tile_y
                FROM claimed ORDER BY segment_id
                """, parameters, (rs, row) -> new ClaimedSegment(rs.getLong(1),
                new OhsomeTile(rs.getInt(2), rs.getInt(3))));
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new OhsomeClaim(claimed.getFirst().tile(), month,
                claimed.stream().map(item -> new OhsomeWorkItem(item.segmentId(), month)).toList()));
    }

    private record ClaimedSegment(long segmentId, OhsomeTile tile) {}

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

    public record ProcessingCounts(long pending, long processing, long enriched, long noData, long errors) {}
}

package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.service.dto.SegmentTileFilter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;

@Repository
public class FilteredSegmentTileRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public FilteredSegmentTileRepository(DataSource dataSource) {
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        jdbc.getJdbcTemplate().setQueryTimeout(30);
    }

    public byte[] tile(int z, int x, int y, SegmentTileFilter filter) {
        var params = new MapSqlParameterSource()
                .addValue("z", z).addValue("x", x).addValue("y", y)
                .addValue("from", filter.from()).addValue("to", filter.to());
        StringBuilder eventPredicate = new StringBuilder("""
                e.segment_id = s.id AND e.event_timestamp BETWEEN :from AND :to
                """);
        if (filter.rideIntent() != null) {
            eventPredicate.append(" AND e.ride_intent = :rideIntent");
            params.addValue("rideIntent", filter.rideIntent().name());
        }
        if (filter.trafficCondition() != null) {
            eventPredicate.append(" AND e.traffic_condition = :trafficCondition");
            params.addValue("trafficCondition", filter.trafficCondition().name());
        }
        for (var enrichment : filter.enrichments()) {
            eventPredicate.append(" AND ").append(switch (enrichment) {
                case WEATHER_ENRICHED -> "e.weather_enriched";
                case OHSOME_ENRICHED -> "e.ohsome_enriched";
                case TRAFFIC_ENRICHED -> "e.traffic_enriched";
                case TRAFFIC_MEASURED -> "e.traffic_enrichment_status = 'ENRICHED'";
                case ROAD_DISRUPTION_AFFECTED -> """
                        EXISTS (SELECT 1 FROM segment_external_factors f
                            WHERE f.segment_id = e.segment_id
                              AND f.source = 'berlin-open-data'
                              AND f.factor_type IN ('CONSTRUCTION', 'ROAD_CLOSURE', 'EVENT', 'HAZARD', 'INCIDENT')
                              AND f.valid_from <= e.event_timestamp
                              AND (f.valid_to IS NULL OR f.valid_to >= e.event_timestamp))
                        """;
            });
        }
        // Overview tiles retain one representative per 8-screen-pixel cell. The grid is
        // global (not tile-relative), and filtering happens before choosing representatives.
        // At z13+ retain every matching segment, as in the PMTiles detail layer.
        boolean overview = z < 13;
        params.addValue("cellSize", 40075016.68557849 / (1 << z) / 64);
        String midpoint = overview
                ? ", ST_Transform(ST_LineInterpolatePoint(s.geometry, 0.5), 3857) AS midpoint" : "";
        String ranked = overview ? """
                , ranked AS (
                    SELECT *, ROW_NUMBER() OVER (
                        PARTITION BY FLOOR(ST_X(midpoint) / :cellSize), FLOOR(ST_Y(midpoint) / :cellSize)
                        ORDER BY event_count DESC, id
                    ) AS density_rank FROM candidates
                )
                """ : "";
        // Keep the spatial index expression in 4326; transform only tile candidates.
        String sql = """
                WITH bounds AS (
                    SELECT ST_TileEnvelope(:z, :x, :y) AS tile,
                           ST_Transform(ST_TileEnvelope(:z, :x, :y, margin => 64.0 / 4096), 4326) AS search
                ), candidates AS (
                    SELECT s.id, s.avoidance_count, s.preference_count,
                           s.avoidance_count + s.preference_count AS event_count,
                           %s AS balance,
                           ST_AsMVTGeom(ST_Transform(s.geometry, 3857), b.tile, 4096, 64, true) AS geom %s
                    FROM street_segments s CROSS JOIN bounds b
                    WHERE s.geometry && b.search
                      AND ST_Intersects(s.geometry, b.search)
                      AND s.usage_count + s.avoidance_count >= 1
                      AND EXISTS (SELECT 1 FROM segment_events e WHERE %s)
                ) %s, features AS (
                    SELECT id, avoidance_count AS "avoidanceCount", preference_count AS "preferenceCount",
                           event_count AS "eventCount", %s AS bucket, geom
                    FROM %s WHERE geom IS NOT NULL %s
                )
                SELECT ST_AsMVT(features, 'segments', 4096, 'geom') FROM features
                """.formatted(TileExportRepository.balanceExpression("s.avoidance_count", "s.preference_count"),
                midpoint, eventPredicate, ranked, TileExportRepository.bucketCase("event_count", "balance"),
                overview ? "ranked" : "candidates", overview ? "AND density_rank = 1" : "");
        byte[] result = jdbc.queryForObject(sql, params, byte[].class);
        return result == null ? new byte[0] : result;
    }
}

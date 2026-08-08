package berlin.tu.cyclinginfrastructurebackend.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.util.Map;

public class StreetSegmentUsageRepositoryImpl implements StreetSegmentUsageRepository {

    private static final String INCREMENT_USAGE_SQL = """
            WITH input AS MATERIALIZED (
                SELECT supplied.edge_id, supplied.usage_delta
                FROM unnest(CAST(? AS bigint[]), CAST(? AS integer[]))
                     AS supplied(edge_id, usage_delta)
            ),
            locked AS MATERIALIZED (
                SELECT s.id
                FROM street_segments s
                JOIN input i ON i.edge_id = s.id
                ORDER BY s.id
                FOR UPDATE OF s
            )
            UPDATE street_segments s
            SET usage_count = s.usage_count + i.usage_delta,
                avoidance_ratio = CAST(s.avoidance_count AS double precision)
                                  / NULLIF(s.usage_count + i.usage_delta + s.avoidance_count, 0),
                preference_ratio = CAST(s.preference_count AS double precision)
                                   / NULLIF(s.usage_count + i.usage_delta, 0)
            FROM input i
            JOIN locked l ON l.id = i.edge_id
            WHERE s.id = i.edge_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public StreetSegmentUsageRepositoryImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int incrementUsageCounts(Map<Long, Integer> usageByEdgeId) {
        if (usageByEdgeId == null || usageByEdgeId.isEmpty()) {
            return 0;
        }

        Long[] edgeIds = usageByEdgeId.keySet().toArray(Long[]::new);
        Integer[] usageDeltas = usageByEdgeId.values().toArray(Integer[]::new);

        Integer updated = jdbcTemplate.execute((ConnectionCallback<Integer>) connection -> {
            Array edgeIdArray = connection.createArrayOf("bigint", edgeIds);
            Array deltaArray = connection.createArrayOf("integer", usageDeltas);
            try (PreparedStatement statement = connection.prepareStatement(INCREMENT_USAGE_SQL)) {
                statement.setArray(1, edgeIdArray);
                statement.setArray(2, deltaArray);
                return statement.executeUpdate();
            } finally {
                edgeIdArray.free();
                deltaArray.free();
            }
        });
        return updated != null ? updated : 0;
    }
}

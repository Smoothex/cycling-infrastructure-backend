package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.AnalyticsFilterOptionsDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Small aggregate results for the explorer's initial page load. */
@Repository
public class AnalyticsReadRepository {
    private final JdbcTemplate jdbc;

    public AnalyticsReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RideCounts rideCounts() {
        // Grouping sets compute both histograms and the total from one table scan.
        return jdbc.query("""
                SELECT status, route_comparison_type, COUNT(*) AS count,
                       GROUPING(status, route_comparison_type) AS grouping
                FROM rides
                GROUP BY GROUPING SETS ((status), (route_comparison_type), ())
                """, rows -> {
            Map<String, Long> statuses = zeroCounts(Status.values());
            Map<String, Long> classifications = zeroCounts(RouteComparisonType.values());
            long total = 0;
            while (rows.next()) {
                long count = rows.getLong("count");
                switch (rows.getInt("grouping")) {
                    case 1 -> statuses.computeIfPresent(rows.getString("status"), (key, value) -> count);
                    case 2 -> classifications.computeIfPresent(
                            rows.getString("route_comparison_type"), (key, value) -> count);
                    case 3 -> total = count;
                    default -> throw new IllegalStateException("Unexpected ride summary grouping");
                }
            }
            return new RideCounts(total, statuses, classifications);
        });
    }

    public EventCounts eventCounts() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       MIN(event_timestamp) AS earliest,
                       MAX(event_timestamp) AS latest,
                       COUNT(*) FILTER (WHERE event_type = 'AVOIDANCE') AS avoidance,
                       COUNT(*) FILTER (WHERE event_type = 'PREFERENCE') AS preference,
                       COUNT(*) FILTER (WHERE weather_enriched) AS weather,
                       COUNT(*) FILTER (WHERE ohsome_enriched) AS ohsome,
                       COUNT(*) FILTER (WHERE berlin_open_data_enriched) AS berlin_open_data,
                       COUNT(*) FILTER (WHERE traffic_enriched) AS traffic,
                       COUNT(*) FILTER (WHERE traffic_enrichment_status = 'ENRICHED') AS measured
                FROM segment_events
                """, (row, index) -> new EventCounts(
                row.getLong("total"), row.getObject("earliest", Long.class),
                row.getObject("latest", Long.class), row.getLong("avoidance"),
                row.getLong("preference"), row.getLong("weather"), row.getLong("ohsome"),
                row.getLong("berlin_open_data"), row.getLong("traffic"), row.getLong("measured")));
    }

    public SegmentCounts segmentCounts() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE usage_count + avoidance_count > 0) AS observed
                FROM street_segments
                """, (row, index) -> new SegmentCounts(row.getLong("total"), row.getLong("observed")));
    }

    public AnalyticsFilterOptionsDto filterOptions() {
        // Dropdowns need observed categories, not the distribution endpoint's joins and averages.
        // Retain frequency ordering; the second sort keys make ties deterministic.
        return jdbc.query("""
                SELECT ride_intent, traffic_condition, COUNT(*) AS event_count,
                       GROUPING(ride_intent) AS intent_grouping
                FROM segment_events
                GROUP BY GROUPING SETS ((ride_intent), (traffic_condition))
                ORDER BY event_count DESC, ride_intent NULLS LAST, traffic_condition NULLS LAST
                """, rows -> {
            var rideIntents = new LinkedHashSet<String>();
            var trafficConditions = new LinkedHashSet<String>();
            while (rows.next()) {
                if (rows.getInt("intent_grouping") == 0) {
                    String value = rows.getString("ride_intent");
                    rideIntents.add(value != null ? value : "UNKNOWN");
                } else {
                    String value = rows.getString("traffic_condition");
                    // The existing traffic dropdown hides both null and explicit UNKNOWN values.
                    if (value != null && !"UNKNOWN".equals(value)) {
                        trafficConditions.add(value);
                    }
                }
            }
            return new AnalyticsFilterOptionsDto(List.copyOf(rideIntents), List.copyOf(trafficConditions));
        });
    }

    private static Map<String, Long> zeroCounts(Enum<?>[] values) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Enum<?> value : values) {
            counts.put(value.name(), 0L);
        }
        return counts;
    }

    public record RideCounts(long total, Map<String, Long> statuses, Map<String, Long> classifications) {}

    public record EventCounts(long total, Long earliest, Long latest, long avoidance, long preference,
                              long weather, long ohsome, long berlinOpenData, long traffic, long measured) {}

    public record SegmentCounts(long total, long observed) {}
}

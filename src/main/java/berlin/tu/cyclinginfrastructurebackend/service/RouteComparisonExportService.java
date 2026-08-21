package berlin.tu.cyclinginfrastructurebackend.service;

import com.opencsv.CSVWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.StringWriter;
import java.util.List;

@Service
public class RouteComparisonExportService {

    private static final String[] HEADER = {
            "ride_id",
            "start_time",
            "baseline_route_comparison_type",
            "actual_distance_m",
            "shortest_path_distance_m",
            "absolute_excess_distance_m",
            "relative_detour_ratio",
            "overlap_ratio",
            "detour_threshold_ratio",
            "maximum_equivalent_excess_distance_m",
            "minimum_overlap_ratio",
            "median_gps_accuracy_m",
            "gps_point_count",
            "actual_route_wkt",
            "shortest_route_wkt",
            "review_label",
            "review_notes"
    };

    private final EntityManager entityManager;
    private final double detourThresholdRatio;
    private final double maximumEquivalentExcessDistanceMeters;
    private final double minimumOverlapRatio;

    public RouteComparisonExportService(
            EntityManager entityManager,
            @Value("${analysis.detour.threshold:0.10}") double detourThresholdRatio,
            @Value("${analysis.detour.maximum-equivalent-excess-meters:500}")
            double maximumEquivalentExcessDistanceMeters,
            @Value("${analysis.route-overlap.minimum-ratio:0.30}") double minimumOverlapRatio) {
        this.entityManager = entityManager;
        this.detourThresholdRatio = detourThresholdRatio;
        this.maximumEquivalentExcessDistanceMeters = maximumEquivalentExcessDistanceMeters;
        this.minimumOverlapRatio = minimumOverlapRatio;
    }

    public String exportCalibrationSample(Long from, Long to, int perType) {
        validateRange(from, to);

        Query query = entityManager.createNativeQuery("""
                WITH comparison_rows AS (
                    SELECT r.id,
                           r.start_time,
                           r.route_comparison_type,
                           r.actual_distance,
                           r.shortest_path_distance,
                           r.actual_distance - r.shortest_path_distance AS absolute_excess_distance,
                           (r.actual_distance - r.shortest_path_distance)
                               / NULLIF(r.shortest_path_distance, 0) AS relative_detour_ratio,
                           r.overlap_ratio,
                           r.median_gps_accuracy,
                           r.gps_point_count,
                           ST_AsText(r.trajectory) AS actual_route_wkt,
                           ST_AsText(r.shortest_path) AS shortest_route_wkt,
                           ROW_NUMBER() OVER (
                               PARTITION BY r.route_comparison_type
                               ORDER BY MD5(CAST(r.id AS text))
                           ) AS sample_rank
                    FROM rides r
                    WHERE r.status = 'PROCESSED'
                      AND r.route_comparison_type IS NOT NULL
                      AND r.actual_distance IS NOT NULL
                      AND r.shortest_path_distance IS NOT NULL
                      AND r.overlap_ratio IS NOT NULL
                      AND r.trajectory IS NOT NULL
                      AND r.shortest_path IS NOT NULL
                      AND r.start_time >= :fromTime
                      AND r.start_time <= :toTime
                )
                SELECT id,
                       start_time,
                       route_comparison_type,
                       actual_distance,
                       shortest_path_distance,
                       absolute_excess_distance,
                       relative_detour_ratio,
                       overlap_ratio,
                       median_gps_accuracy,
                       gps_point_count,
                       actual_route_wkt,
                       shortest_route_wkt
                FROM comparison_rows
                WHERE sample_rank <= :perType
                ORDER BY route_comparison_type, sample_rank
                """)
                .setParameter("fromTime", from != null ? from : Long.MIN_VALUE)
                .setParameter("toTime", to != null ? to : Long.MAX_VALUE)
                .setParameter("perType", perType);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return toCsv(rows);
    }

    private String toCsv(List<Object[]> rows) {
        StringWriter output = new StringWriter();
        try (CSVWriter csvWriter = new CSVWriter(output)) {
            csvWriter.writeNext(HEADER);
            for (Object[] row : rows) {
                csvWriter.writeNext(new String[]{
                        value(row[0]),
                        value(row[1]),
                        value(row[2]),
                        value(row[3]),
                        value(row[4]),
                        value(row[5]),
                        value(row[6]),
                        value(row[7]),
                        value(detourThresholdRatio),
                        value(maximumEquivalentExcessDistanceMeters),
                        value(minimumOverlapRatio),
                        value(row[8]),
                        value(row[9]),
                        value(row[10]),
                        value(row[11]),
                        "",
                        ""
                });
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not create route-comparison CSV", e);
        }
        return output.toString();
    }

    private String value(Object value) {
        return value != null ? value.toString() : "";
    }

    private void validateRange(Long from, Long to) {
        if (from != null && to != null && from > to) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
        }
    }
}

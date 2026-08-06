package berlin.tu.cyclinginfrastructurebackend.service;

import com.opencsv.CSVWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
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
            "median_gps_accuracy_m",
            "gps_point_count",
            "actual_route_wkt",
            "shortest_route_wkt",
            "review_label",
            "review_notes"
    };

    private final EntityManager entityManager;

    public RouteComparisonExportService(EntityManager entityManager) {
        this.entityManager = entityManager;
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
                           (SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY rp.gps_accuracy)
                            FROM ride_points rp
                            WHERE rp.ride_id = r.id AND rp.gps_accuracy IS NOT NULL) AS median_gps_accuracy,
                           (SELECT COUNT(*) FROM ride_points rp WHERE rp.ride_id = r.id) AS gps_point_count,
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

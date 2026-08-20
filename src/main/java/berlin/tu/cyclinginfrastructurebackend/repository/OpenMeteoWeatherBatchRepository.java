package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo.OpenMeteoGridYear;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo.OpenMeteoHourlyRow;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo.OpenMeteoLocation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Set-based persistence and claiming for bulk Open-Meteo enrichment. */
@Repository
public class OpenMeteoWeatherBatchRepository {

    private static final long HOUR_MILLIS = 3_600_000L;
    private static final int CACHE_INSERT_BATCH_SIZE = 1_000;
    private static final String CLEAR_WEATHER_FIELDS = """
            weather_enriched = false,
            temperature2m = NULL,
            precipitation = NULL,
            wind_speed10m = NULL,
            wind_direction10m = NULL,
            weather_code = NULL,
            relative_wind_angle_degrees = NULL,
            wind_exposure = NULL
            """;

    private final JdbcTemplate jdbcTemplate;

    public OpenMeteoWeatherBatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean hasPendingWork() {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM segment_events WHERE weather_processing_status = 'PENDING'
                )
                """, Boolean.class));
    }

    /** Creates immutable segment-to-grid assignments from valid WGS84 centroids. */
    @Transactional
    public int populateMissingSegmentGrids() {
        return jdbcTemplate.update("""
                INSERT INTO open_meteo_segment_grid (
                    segment_id, latitude_tenths, longitude_tenths
                )
                SELECT s.id,
                       ROUND((ST_Y(c.centroid) * 10)::numeric)::integer,
                       ROUND((ST_X(c.centroid) * 10)::numeric)::integer
                FROM street_segments s
                CROSS JOIN LATERAL (SELECT ST_Centroid(s.geometry) AS centroid) c
                WHERE NOT EXISTS (
                          SELECT 1 FROM open_meteo_segment_grid g WHERE g.segment_id = s.id
                      )
                  AND EXISTS (
                          SELECT 1
                          FROM segment_events e
                          WHERE e.segment_id = s.id
                            AND e.weather_processing_status = 'PENDING'
                      )
                  AND s.geometry IS NOT NULL
                  AND NOT ST_IsEmpty(s.geometry)
                  AND ST_IsValid(s.geometry)
                  AND ST_SRID(s.geometry) = 4326
                  AND ST_Y(c.centroid) BETWEEN -90 AND 90
                  AND ST_X(c.centroid) BETWEEN -180 AND 180
                ON CONFLICT (segment_id) DO NOTHING
                """);
    }

    /** Marks pending events that cannot be assigned a timestamped grid/hour as errors. */
    @Transactional
    public int markInvalidPendingEvents() {
        return jdbcTemplate.update("""
                UPDATE segment_events e
                SET weather_processing_status = 'ERROR',
                    %s
                WHERE e.weather_processing_status = 'PENDING'
                  AND (
                      e.event_timestamp IS NULL
                      OR NOT EXISTS (
                          SELECT 1 FROM open_meteo_segment_grid g WHERE g.segment_id = e.segment_id
                      )
                  )
                """.formatted(CLEAR_WEATHER_FIELDS));
    }

    /** Claims every event for up to {@code batchSize} locations in the earliest pending UTC year. */
    @Transactional
    public List<OpenMeteoGridYear> claimNextBatch(int batchSize) {
        Long earliestTimestamp = jdbcTemplate.queryForObject("""
                SELECT MIN(e.event_timestamp)
                FROM segment_events e
                JOIN open_meteo_segment_grid g ON g.segment_id = e.segment_id
                WHERE e.weather_processing_status = 'PENDING'
                  AND e.event_timestamp IS NOT NULL
                """, Long.class);
        if (earliestTimestamp == null) {
            return List.of();
        }

        int year = Instant.ofEpochMilli(earliestTimestamp).atZone(ZoneOffset.UTC).getYear();
        OpenMeteoGridYear yearBounds = new OpenMeteoGridYear(0, 0, year);
        return jdbcTemplate.query("""
                WITH selected_locations AS MATERIALIZED (
                    SELECT g.latitude_tenths, g.longitude_tenths
                    FROM segment_events e
                    JOIN open_meteo_segment_grid g ON g.segment_id = e.segment_id
                    WHERE e.weather_processing_status = 'PENDING'
                      AND e.event_timestamp >= ?
                      AND e.event_timestamp < ?
                    GROUP BY g.latitude_tenths, g.longitude_tenths
                    ORDER BY g.latitude_tenths, g.longitude_tenths
                    LIMIT ?
                ), claimed AS (
                    UPDATE segment_events e
                    SET weather_processing_status = 'PROCESSING'
                    FROM open_meteo_segment_grid g
                    JOIN selected_locations selected
                      ON selected.latitude_tenths = g.latitude_tenths
                     AND selected.longitude_tenths = g.longitude_tenths
                    WHERE e.segment_id = g.segment_id
                      AND e.weather_processing_status = 'PENDING'
                      AND e.event_timestamp >= ?
                      AND e.event_timestamp < ?
                    RETURNING g.latitude_tenths, g.longitude_tenths
                )
                SELECT latitude_tenths, longitude_tenths
                FROM claimed
                GROUP BY latitude_tenths, longitude_tenths
                ORDER BY latitude_tenths, longitude_tenths
                """, (resultSet, rowNum) -> new OpenMeteoGridYear(
                        resultSet.getInt(1), resultSet.getInt(2), year),
                yearBounds.yearStartMillis(),
                yearBounds.nextYearStartMillis(),
                Math.max(1, Math.min(5, batchSize)),
                yearBounds.yearStartMillis(),
                yearBounds.nextYearStartMillis());
    }

    /** Applies cached hourly weather and finalizes every matching claimed event. */
    @Transactional
    public int applyCachedWeather(List<OpenMeteoGridYear> claimed) {
        if (claimed.isEmpty()) {
            return 0;
        }
        PairScope scope = pairScope(claimed);
        return jdbcTemplate.update("""
                WITH %s,
                weather_matches AS (
                    SELECT e.id,
                           w.temperature2m,
                           w.precipitation,
                           w.wind_speed10m,
                           w.wind_direction10m,
                           w.weather_code,
                           CASE
                               WHEN e.path_bearing_degrees IS NULL OR w.wind_direction10m IS NULL THEN NULL
                               ELSE ABS(
                                   (w.wind_direction10m - e.path_bearing_degrees)
                                   - 360.0 * FLOOR(
                                       ((w.wind_direction10m - e.path_bearing_degrees) + 180.0) / 360.0
                                   )
                               )
                           END AS relative_angle
                    FROM segment_events e
                    JOIN open_meteo_segment_grid g ON g.segment_id = e.segment_id
                    JOIN claimed_pairs p
                      ON p.latitude_tenths = g.latitude_tenths
                     AND p.longitude_tenths = g.longitude_tenths
                     AND e.event_timestamp >= p.year_start
                     AND e.event_timestamp < p.year_end
                    JOIN open_meteo_hourly_weather w
                      ON w.latitude_tenths = g.latitude_tenths
                     AND w.longitude_tenths = g.longitude_tenths
                     AND w.valid_from = (
                         FLOOR(e.event_timestamp::numeric / %d) * %d
                     )::bigint
                    WHERE e.weather_processing_status = 'PROCESSING'
                )
                UPDATE segment_events e
                SET weather_processing_status = 'DONE',
                    weather_enriched = true,
                    temperature2m = matched.temperature2m,
                    precipitation = matched.precipitation,
                    wind_speed10m = matched.wind_speed10m,
                    wind_direction10m = matched.wind_direction10m,
                    weather_code = matched.weather_code,
                    relative_wind_angle_degrees = matched.relative_angle,
                    wind_exposure = CASE
                        WHEN matched.relative_angle IS NULL THEN NULL
                        WHEN matched.relative_angle <= 45.0 THEN 'HEADWIND'
                        WHEN matched.relative_angle >= 135.0 THEN 'TAILWIND'
                        ELSE 'CROSSWIND'
                    END
                FROM weather_matches matched
                WHERE e.id = matched.id
                  AND e.weather_processing_status = 'PROCESSING'
                """.formatted(scope.cte(), HOUR_MILLIS, HOUR_MILLIS), scope.arguments());
    }

    public int countProcessingEvents(List<OpenMeteoGridYear> claimed) {
        if (claimed.isEmpty()) {
            return 0;
        }
        PairScope scope = pairScope(claimed);
        Integer count = jdbcTemplate.queryForObject("""
                WITH %s
                SELECT COUNT(*)::integer
                FROM segment_events e
                JOIN open_meteo_segment_grid g ON g.segment_id = e.segment_id
                JOIN claimed_pairs p
                  ON p.latitude_tenths = g.latitude_tenths
                 AND p.longitude_tenths = g.longitude_tenths
                 AND e.event_timestamp >= p.year_start
                 AND e.event_timestamp < p.year_end
                WHERE e.weather_processing_status = 'PROCESSING'
                """.formatted(scope.cte()), Integer.class, scope.arguments());
        return Objects.requireNonNullElse(count, 0);
    }

    public List<OpenMeteoLocation> findMissingLocations(List<OpenMeteoGridYear> claimed) {
        if (claimed.isEmpty()) {
            return List.of();
        }
        PairScope scope = pairScope(claimed);
        return jdbcTemplate.query("""
                WITH %s
                SELECT g.latitude_tenths, g.longitude_tenths
                FROM segment_events e
                JOIN open_meteo_segment_grid g ON g.segment_id = e.segment_id
                JOIN claimed_pairs p
                  ON p.latitude_tenths = g.latitude_tenths
                 AND p.longitude_tenths = g.longitude_tenths
                 AND e.event_timestamp >= p.year_start
                 AND e.event_timestamp < p.year_end
                WHERE e.weather_processing_status = 'PROCESSING'
                GROUP BY g.latitude_tenths, g.longitude_tenths
                ORDER BY g.latitude_tenths, g.longitude_tenths
                """.formatted(scope.cte()),
                (resultSet, rowNum) -> new OpenMeteoLocation(resultSet.getInt(1), resultSet.getInt(2)),
                scope.arguments());
    }

    public MissingWindow findMissingWindow(List<OpenMeteoGridYear> claimed) {
        if (claimed.isEmpty()) {
            return null;
        }
        PairScope scope = pairScope(claimed);
        return jdbcTemplate.queryForObject("""
                WITH %s
                SELECT MIN(e.event_timestamp), MAX(e.event_timestamp)
                FROM segment_events e
                JOIN open_meteo_segment_grid g ON g.segment_id = e.segment_id
                JOIN claimed_pairs p
                  ON p.latitude_tenths = g.latitude_tenths
                 AND p.longitude_tenths = g.longitude_tenths
                 AND e.event_timestamp >= p.year_start
                 AND e.event_timestamp < p.year_end
                WHERE e.weather_processing_status = 'PROCESSING'
                """.formatted(scope.cte()), (resultSet, rowNum) -> {
            Long minimum = resultSet.getObject(1, Long.class);
            Long maximum = resultSet.getObject(2, Long.class);
            return minimum == null || maximum == null ? null : new MissingWindow(minimum, maximum);
        }, scope.arguments());
    }

    /** Idempotently writes a fully validated response in bounded JDBC batches. */
    @Transactional
    public int upsertHourlyWeather(List<OpenMeteoHourlyRow> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO open_meteo_hourly_weather (
                    latitude_tenths, longitude_tenths, valid_from,
                    temperature2m, precipitation, wind_speed10m,
                    wind_direction10m, weather_code
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (latitude_tenths, longitude_tenths, valid_from)
                DO UPDATE SET
                    temperature2m = EXCLUDED.temperature2m,
                    precipitation = EXCLUDED.precipitation,
                    wind_speed10m = EXCLUDED.wind_speed10m,
                    wind_direction10m = EXCLUDED.wind_direction10m,
                    weather_code = EXCLUDED.weather_code
                """, rows, CACHE_INSERT_BATCH_SIZE, (statement, row) -> {
            statement.setInt(1, row.latitudeTenths());
            statement.setInt(2, row.longitudeTenths());
            statement.setLong(3, row.validFrom());
            setNullableDouble(statement, 4, row.temperature2m());
            setNullableDouble(statement, 5, row.precipitation());
            setNullableDouble(statement, 6, row.windSpeed10m());
            setNullableDouble(statement, 7, row.windDirection10m());
            if (row.weatherCode() == null) {
                statement.setNull(8, Types.INTEGER);
            } else {
                statement.setInt(8, row.weatherCode());
            }
        });
        return rows.size();
    }

    /** Marks only still-unmatched events in the claimed tuples as errors. */
    @Transactional
    public int markRemainingEventsError(List<OpenMeteoGridYear> claimed) {
        return updateClaimedStatus(claimed, "ERROR", true);
    }

    /** Releases only still-processing events in the claimed tuples for a later retry. */
    @Transactional
    public int releaseBatch(List<OpenMeteoGridYear> claimed) {
        return updateClaimedStatus(claimed, "PENDING", false);
    }

    private int updateClaimedStatus(List<OpenMeteoGridYear> claimed, String status, boolean clearWeather) {
        if (claimed.isEmpty()) {
            return 0;
        }
        PairScope scope = pairScope(claimed);
        String assignments = clearWeather
                ? "weather_processing_status = '" + status + "', " + CLEAR_WEATHER_FIELDS
                : "weather_processing_status = '" + status + "'";
        return jdbcTemplate.update("""
                WITH %s
                UPDATE segment_events e
                SET %s
                FROM open_meteo_segment_grid g
                JOIN claimed_pairs p
                  ON p.latitude_tenths = g.latitude_tenths
                 AND p.longitude_tenths = g.longitude_tenths
                WHERE e.segment_id = g.segment_id
                  AND e.event_timestamp >= p.year_start
                  AND e.event_timestamp < p.year_end
                  AND e.weather_processing_status = 'PROCESSING'
                """.formatted(scope.cte(), assignments), scope.arguments());
    }

    private PairScope pairScope(List<OpenMeteoGridYear> claimed) {
        String placeholders = claimed.stream()
                .map(ignored -> "(?, ?, ?, ?)")
                .collect(Collectors.joining(", "));
        List<Object> arguments = new ArrayList<>(claimed.size() * 4);
        for (OpenMeteoGridYear item : claimed) {
            arguments.add(item.latitudeTenths());
            arguments.add(item.longitudeTenths());
            arguments.add(item.yearStartMillis());
            arguments.add(item.nextYearStartMillis());
        }
        return new PairScope("claimed_pairs(latitude_tenths, longitude_tenths, year_start, year_end) AS (VALUES "
                + placeholders + ")", arguments.toArray());
    }

    private static void setNullableDouble(java.sql.PreparedStatement statement,
                                          int parameterIndex,
                                          Double value) throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(parameterIndex, Types.DOUBLE);
        } else {
            statement.setDouble(parameterIndex, value);
        }
    }

    public record MissingWindow(long minimumTimestamp, long maximumTimestamp) {
    }

    private record PairScope(String cte, Object[] arguments) {
    }
}

package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo.OpenMeteoClaim;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo.OpenMeteoGridYear;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo.OpenMeteoHourlyRow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class OpenMeteoWeatherBatchRepositoryIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName
            .parse("postgis/postgis:17-3.4")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("cyclingdb")
            .withUsername("user")
            .withPassword("password")
            .withStartupTimeout(Duration.ofMinutes(2));

    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static OpenMeteoWeatherBatchRepository repository;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void connect() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new OpenMeteoWeatherBatchRepository(jdbcTemplate);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS postgis");
    }

    @BeforeEach
    void createSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS open_meteo_hourly_weather");
        jdbcTemplate.execute("DROP TABLE IF EXISTS open_meteo_segment_grid");
        jdbcTemplate.execute("DROP TABLE IF EXISTS segment_events");
        jdbcTemplate.execute("DROP TABLE IF EXISTS street_segments");
        jdbcTemplate.execute("""
                CREATE TABLE street_segments (
                    id bigint PRIMARY KEY,
                    geometry geometry(LineString, 4326)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE segment_events (
                    id bigint PRIMARY KEY,
                    segment_id bigint NOT NULL REFERENCES street_segments(id),
                    event_timestamp bigint,
                    weather_processing_status varchar(20) NOT NULL,
                    weather_processing_batch_id uuid,
                    weather_enriched boolean NOT NULL DEFAULT false,
                    temperature2m double precision,
                    precipitation double precision,
                    wind_speed10m double precision,
                    wind_direction10m double precision,
                    weather_code integer,
                    path_bearing_degrees double precision,
                    relative_wind_angle_degrees double precision,
                    wind_exposure varchar(20)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE open_meteo_segment_grid (
                    segment_id bigint PRIMARY KEY,
                    latitude_tenths integer NOT NULL,
                    longitude_tenths integer NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE open_meteo_hourly_weather (
                    latitude_tenths integer NOT NULL,
                    longitude_tenths integer NOT NULL,
                    valid_from bigint NOT NULL,
                    temperature2m double precision,
                    precipitation double precision,
                    wind_speed10m double precision,
                    wind_direction10m double precision,
                    weather_code integer,
                    PRIMARY KEY (latitude_tenths, longitude_tenths, valid_from)
                )
                """);
    }

    @Test
    void roundsSegmentCentroidsToIntegerTenthsAtHalfBoundaries() {
        insertSegment(10, "LINESTRING(13.24 52.24, 13.26 52.26)");
        insertSegment(20, "LINESTRING(13.248 52.248, 13.250 52.250)");
        insertEvent(1, 10, timestamp("2024-01-01T00:00:00Z"), 0.0);
        insertEvent(2, 20, timestamp("2024-01-01T00:00:00Z"), 0.0);

        assertThat(repository.populateMissingSegmentGrids()).isEqualTo(2);

        assertThat(grid(10)).containsExactly(523, 133);
        assertThat(grid(20)).containsExactly(522, 132);
        assertThat(repository.populateMissingSegmentGrids()).isZero();
    }

    @Test
    void mapsDegenerateLineWhenItsCentroidIsUsable() {
        insertSegment(10, "LINESTRING(13.5241148 52.4595798, 13.5241148 52.4595798)");
        insertEvent(1, 10, timestamp("2024-01-01T00:00:00Z"), 0.0);

        assertThat(repository.populateMissingSegmentGrids()).isEqualTo(1);
        assertThat(repository.markInvalidPendingEvents()).isZero();
        assertThat(grid(10)).containsExactly(525, 135);
    }

    @Test
    void oneCachedGridHourUpdatesEventsAcrossMultipleSegments() {
        insertSegment(10, "LINESTRING(13.36 52.46, 13.38 52.48)");
        insertSegment(20, "LINESTRING(13.35 52.45, 13.39 52.49)");
        long hour = timestamp("2024-01-10T12:00:00Z");
        insertEvent(1, 10, hour + 5_000, 90.0);
        insertEvent(2, 20, hour + 3_599_000, 90.0);
        preparePendingWork();

        OpenMeteoClaim claimed = claim(1);
        assertThat(claimed.gridYears()).containsExactly(new OpenMeteoGridYear(525, 134, 2024));
        assertThat(repository.hasCachedWeather(claimed)).isFalse();
        repository.upsertHourlyWeather(List.of(
                new OpenMeteoHourlyRow(525, 134, hour, 5.0, 0.2, 12.0, 180.0, 61)));
        assertThat(repository.hasCachedWeather(claimed)).isTrue();

        assertThat(repository.applyCachedWeather(claimed)).isEqualTo(2);
        assertThat(eventsInBatch(claimed)).isZero();

        assertThat(eventState(1)).isEqualTo(
                new EventState("DONE", true, 5.0, 0.2, 12.0, 180.0, 61, 90.0, "CROSSWIND"));
        assertThat(eventState(2)).isEqualTo(eventState(1));
        assertThat(repository.applyCachedWeather(claimed)).isZero();
    }

    @Test
    void claimsDistinctLocationsFromTheEarliestUtcYear() {
        insertSegment(10, "LINESTRING(12.99 51.99, 13.01 52.01)");
        insertSegment(20, "LINESTRING(13.19 52.19, 13.21 52.21)");
        insertSegment(30, "LINESTRING(13.39 52.39, 13.41 52.41)");
        insertEvent(1, 10, timestamp("2023-01-01T00:00:00Z"), 0.0);
        insertEvent(2, 20, timestamp("2023-12-31T23:59:59Z"), 0.0);
        insertEvent(3, 30, timestamp("2024-01-01T00:00:00Z"), 0.0);
        preparePendingWork();

        OpenMeteoClaim first = claim(2);
        OpenMeteoClaim second = claim(2);

        assertThat(first.gridYears()).containsExactly(
                new OpenMeteoGridYear(520, 130, 2023),
                new OpenMeteoGridYear(522, 132, 2023));
        assertThat(second.gridYears()).containsExactly(new OpenMeteoGridYear(524, 134, 2024));
    }

    @Test
    void claimsAndReleasesAreBoundedAndIsolatedByBatchId() {
        insertSegment(10, "LINESTRING(12.99 51.99, 13.01 52.01)");
        insertEvent(1, 10, timestamp("2024-01-01T00:00:00Z"), 0.0);
        insertEvent(2, 10, timestamp("2024-01-01T01:00:00Z"), 0.0);
        insertEvent(3, 10, timestamp("2024-01-01T02:00:00Z"), 0.0);
        preparePendingWork();

        OpenMeteoClaim first = claim(1, 2);
        OpenMeteoClaim second = claim(1, 2);

        assertThat(first.eventCount()).isEqualTo(2);
        assertThat(second.eventCount()).isEqualTo(1);
        assertThat(second.batchId()).isNotEqualTo(first.batchId());
        assertThat(eventsInBatch(first)).isEqualTo(2);
        assertThat(eventsInBatch(second)).isEqualTo(1);

        assertThat(repository.releaseBatch(first)).isEqualTo(2);
        assertThat(eventsInBatch(first)).isZero();
        assertThat(eventsInBatch(second)).isEqualTo(1);
        assertThat(statusCount("PENDING")).isEqualTo(2);
        assertThat(statusCount("PROCESSING")).isEqualTo(1);
    }

    @Test
    void cacheUpsertAndReleasedClaimsAreIdempotent() {
        insertSegment(10, "LINESTRING(12.99 51.99, 13.01 52.01)");
        long hour = timestamp("2024-01-01T00:00:00Z");
        insertEvent(1, 10, hour, 0.0);
        preparePendingWork();
        OpenMeteoClaim firstClaim = claim(1);

        assertThat(repository.releaseBatch(firstClaim)).isEqualTo(1);
        OpenMeteoClaim secondClaim = claim(1);
        assertThat(secondClaim.gridYears()).isEqualTo(firstClaim.gridYears());
        assertThat(secondClaim.batchId()).isNotEqualTo(firstClaim.batchId());

        OpenMeteoHourlyRow first = new OpenMeteoHourlyRow(520, 130, hour, 1.0, 2.0, 3.0, 4.0, 5);
        OpenMeteoHourlyRow replacement = new OpenMeteoHourlyRow(520, 130, hour, 6.0, 7.0, 8.0, 9.0, 10);
        repository.upsertHourlyWeather(List.of(first));
        repository.upsertHourlyWeather(List.of(replacement));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*)::integer FROM open_meteo_hourly_weather", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT temperature2m FROM open_meteo_hourly_weather", Double.class)).isEqualTo(6.0);
    }

    @Test
    void normalizesWindAnglesAndIncludesClassificationBoundaries() {
        insertSegment(10, "LINESTRING(12.99 51.99, 13.01 52.01)");
        long hour = timestamp("2024-01-01T00:00:00Z");
        insertEvent(1, 10, hour, 350.0);
        insertEvent(2, 10, hour, 305.0);
        insertEvent(3, 10, hour, 260.0);
        insertEvent(4, 10, hour, 215.0);
        insertEvent(5, 10, hour, 170.0);
        insertEvent(6, 10, hour, 10.0);
        insertEvent(7, 10, hour, null);
        preparePendingWork();
        OpenMeteoClaim claimed = claim(1);
        repository.upsertHourlyWeather(List.of(
                new OpenMeteoHourlyRow(520, 130, hour, null, null, null, 350.0, null)));

        assertThat(repository.applyCachedWeather(claimed)).isEqualTo(7);

        assertAngle(1, 0.0, "HEADWIND");
        assertAngle(2, 45.0, "HEADWIND");
        assertAngle(3, 90.0, "CROSSWIND");
        assertAngle(4, 135.0, "TAILWIND");
        assertAngle(5, 180.0, "TAILWIND");
        assertAngle(6, 20.0, "HEADWIND");
        assertAngle(7, null, null);
    }

    @Test
    void nullWindDirectionLeavesBothDerivedFieldsNull() {
        insertSegment(10, "LINESTRING(12.99 51.99, 13.01 52.01)");
        long hour = timestamp("2024-01-01T00:00:00Z");
        insertEvent(1, 10, hour, 90.0);
        preparePendingWork();
        OpenMeteoClaim claimed = claim(1);
        repository.upsertHourlyWeather(List.of(
                new OpenMeteoHourlyRow(520, 130, hour, 1.0, 0.0, null, null, 0)));

        repository.applyCachedWeather(claimed);

        assertAngle(1, null, null);
        assertThat(eventState(1).enriched()).isTrue();
    }

    @Test
    void invalidInputsAndMissingSuccessfulResponseHoursEndAsErrors() {
        insertSegment(10, null);
        insertSegment(20, "LINESTRING(13.19 52.19, 13.21 52.21)");
        insertEvent(1, 10, timestamp("2024-01-01T00:00:00Z"), 0.0);
        insertEvent(2, 20, null, 0.0);
        insertEvent(3, 20, timestamp("2024-01-01T00:00:00Z"), 0.0);

        assertThat(repository.populateMissingSegmentGrids()).isEqualTo(1);
        assertThat(repository.markInvalidPendingEvents()).isEqualTo(2);
        OpenMeteoClaim claimed = claim(1);
        assertThat(repository.applyCachedWeather(claimed)).isZero();
        assertThat(repository.markRemainingEventsError(claimed)).isEqualTo(1);
        assertThat(eventsInBatch(claimed)).isZero();

        assertThat(statuses()).containsExactly("ERROR", "ERROR", "ERROR");
        assertThat(repository.hasPendingWork()).isFalse();
    }

    @Test
    void missingResponseHourMarksOnlyTheUnmatchedEventError() {
        insertSegment(10, "LINESTRING(12.99 51.99, 13.01 52.01)");
        long firstHour = timestamp("2024-01-01T00:00:00Z");
        long secondHour = timestamp("2024-01-01T01:00:00Z");
        insertEvent(1, 10, firstHour, 0.0);
        insertEvent(2, 10, secondHour, 0.0);
        preparePendingWork();
        OpenMeteoClaim claimed = claim(1);
        repository.upsertHourlyWeather(List.of(
                new OpenMeteoHourlyRow(520, 130, firstHour, 1.0, 0.0, 2.0, 0.0, 3)));

        assertThat(repository.applyCachedWeather(claimed)).isEqualTo(1);
        assertThat(repository.markRemainingEventsError(claimed)).isEqualTo(1);

        assertThat(statuses()).containsExactly("DONE", "ERROR");
        assertThat(eventState(1).enriched()).isTrue();
        assertThat(eventState(2).enriched()).isFalse();
    }

    private void preparePendingWork() {
        repository.populateMissingSegmentGrids();
        assertThat(repository.markInvalidPendingEvents()).isZero();
    }

    private OpenMeteoClaim claim(int locationBatchSize) {
        return claim(locationBatchSize, 100);
    }

    private OpenMeteoClaim claim(int locationBatchSize, int eventBatchSize) {
        return inTransaction(() -> repository.claimNextBatch(locationBatchSize, eventBatchSize))
                .orElseThrow();
    }

    private void insertSegment(long id, String wkt) {
        if (wkt == null) {
            jdbcTemplate.update("INSERT INTO street_segments (id, geometry) VALUES (?, NULL)", id);
        } else {
            jdbcTemplate.update("""
                    INSERT INTO street_segments (id, geometry)
                    VALUES (?, ST_GeomFromText(?, 4326))
                    """, id, wkt);
        }
    }

    private void insertEvent(long id, long segmentId, Long timestamp, Double bearing) {
        jdbcTemplate.update("""
                INSERT INTO segment_events (
                    id, segment_id, event_timestamp, path_bearing_degrees,
                    weather_processing_status, weather_enriched
                ) VALUES (?, ?, ?, ?, 'PENDING', false)
                """, id, segmentId, timestamp, bearing);
    }

    private List<Integer> grid(long segmentId) {
        return jdbcTemplate.queryForObject("""
                SELECT ARRAY[latitude_tenths, longitude_tenths]
                FROM open_meteo_segment_grid
                WHERE segment_id = ?
                """, (resultSet, rowNum) -> List.of(
                resultSet.getArray(1).getArray() instanceof Integer[] values ? values : new Integer[0]), segmentId);
    }

    private EventState eventState(long eventId) {
        return jdbcTemplate.queryForObject("""
                SELECT weather_processing_status, weather_enriched, temperature2m, precipitation,
                       wind_speed10m, wind_direction10m, weather_code,
                       relative_wind_angle_degrees, wind_exposure
                FROM segment_events
                WHERE id = ?
                """, (resultSet, rowNum) -> new EventState(
                resultSet.getString(1),
                resultSet.getBoolean(2),
                resultSet.getObject(3, Double.class),
                resultSet.getObject(4, Double.class),
                resultSet.getObject(5, Double.class),
                resultSet.getObject(6, Double.class),
                resultSet.getObject(7, Integer.class),
                resultSet.getObject(8, Double.class),
                resultSet.getString(9)), eventId);
    }

    private void assertAngle(long eventId, Double angle, String exposure) {
        EventState state = eventState(eventId);
        assertThat(state.relativeAngle()).isEqualTo(angle);
        assertThat(state.exposure()).isEqualTo(exposure);
    }

    private List<String> statuses() {
        return jdbcTemplate.queryForList(
                "SELECT weather_processing_status FROM segment_events ORDER BY id", String.class);
    }

    private int eventsInBatch(OpenMeteoClaim claim) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)::integer
                FROM segment_events
                WHERE weather_processing_batch_id = ?
                """, Integer.class, claim.batchId());
    }

    private int statusCount(String status) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)::integer
                FROM segment_events
                WHERE weather_processing_status = ?
                """, Integer.class, status);
    }

    private static long timestamp(String value) {
        return Instant.parse(value).toEpochMilli();
    }

    private static <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }

    private record EventState(
            String status,
            boolean enriched,
            Double temperature,
            Double precipitation,
            Double windSpeed,
            Double windDirection,
            Integer weatherCode,
            Double relativeAngle,
            String exposure
    ) {
    }
}

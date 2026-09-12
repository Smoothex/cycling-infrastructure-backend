package berlin.tu.cyclinginfrastructurebackend.repository;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class CorridorRankingRepositoryIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName
            .parse("postgis/postgis:17-3.4").asCompatibleSubstituteFor("postgres"))
            .withStartupTimeout(Duration.ofMinutes(2));

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static String rankingSql;

    @BeforeAll
    static void connect() throws Exception {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        rankingSql = StreetSegmentRepository.class
                .getMethod("findCorridorRankings", String.class, int.class, int.class,
                        long.class, long.class, String.class)
                .getAnnotation(Query.class).value();
    }

    @BeforeEach
    void createSchema() {
        jdbc.execute("DROP TABLE IF EXISTS incidents, segment_events, street_segments, rides");
        jdbc.execute("CREATE TABLE rides (id uuid PRIMARY KEY, ride_intent text)");
        jdbc.execute("CREATE TABLE street_segments (id bigint PRIMARY KEY, street_name text, geometry geometry(LineString,4326))");
        jdbc.execute("""
                CREATE TABLE segment_events (
                    id uuid PRIMARY KEY, segment_id bigint, ride_id uuid,
                    event_type text, event_timestamp bigint, ride_intent text)
                """);
        jdbc.execute("""
                CREATE TABLE incidents (
                    id uuid PRIMARY KEY, ride_id uuid, scary boolean,
                    timestamp bigint, location geometry(Point,4326))
                """);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    }

    @Test
    void preservesDistinctRideCountsFullMembershipAndIncidentFilters() {
        insertSharedCorridor();
        // The same incident is near both segments and must still count only once.
        incident(4, "COMMUTE", true, 1_000, "POINT(13.401 52.5)");
        incident(5, "LEISURE", true, 2_000, "POINT(13.401 52.5)");
        incident(4, "COMMUTE", false, 1_500, "POINT(13.401 52.5)");
        incident(4, "COMMUTE", true, 999, "POINT(13.401 52.5)");
        incident(4, "COMMUTE", true, 2_001, "POINT(13.401 52.5)");
        incident(4, "COMMUTE", true, 1_500, "POINT(11.075 49.45)");
        incident(4, "COMMUTE", true, 1_500, null);

        assertThat(rank("AVOIDANCE", 1, 50, "")).singleElement().satisfies(row -> {
            assertThat((String) row.get("street_name")).isEqualToIgnoringCase("Main Street");
            assertThat(row.get("avoidance_rides")).isEqualTo(2L);
            assertThat(row.get("preference_rides")).isEqualTo(1L);
            assertThat(row.get("avoidance_events")).isEqualTo(4L);
            assertThat(row.get("preference_events")).isEqualTo(3L);
            assertThat(row.get("segment_count")).isEqualTo(2L);
            assertThat(row.get("segment_ids")).isEqualTo("10,20");
            assertThat(row.get("top_segment_id")).isEqualTo(10L);
            assertThat(row.get("scary_incidents")).isEqualTo(2L);
            assertThat((Double) row.get("min_lon")).isEqualTo(13.4);
            assertThat((Double) row.get("max_lon")).isEqualTo(13.402);
        });
        assertThat(rank("AVOIDANCE", 1, 50, "COMMUTE")).singleElement()
                .satisfies(row -> assertThat(row.get("scary_incidents")).isEqualTo(1L));
        assertThat(rank("AVOIDANCE", 1, 50, "LEISURE")).isEmpty();
    }

    @Test
    void ranksBeforeGeometryAggregationWithoutMergingCitiesOrLosingZeroIncidentRows() {
        insertSharedCorridor();
        segment(30, "Main Street", "LINESTRING(11.575 48.137,11.576 48.137)");
        for (int ride = 10; ride < 13; ride++) {
            event(30, ride, "AVOIDANCE", 1_500);
        }

        assertThat(rank("AVOIDANCE", 1, 1, "")).singleElement().satisfies(row -> {
            assertThat(row.get("avoidance_rides")).isEqualTo(3L);
            assertThat(row.get("segment_count")).isEqualTo(1L);
            assertThat(row.get("segment_ids")).isEqualTo("30");
            assertThat(row.get("scary_incidents")).isEqualTo(0L);
            assertThat(row.get("min_lon")).isEqualTo(11.575);
        });
        assertThat(rank("PREFERENCE", 1, 1, "")).singleElement().satisfies(row -> {
            assertThat(row.get("segment_ids")).isEqualTo("10,20");
            assertThat(row.get("preference_rides")).isEqualTo(1L);
        });
        assertThat(rank("PREFERENCE", 2, 50, "")).isEmpty();
    }

    @Test
    void keepsEventCountTieBreakAndRestrictsCorridorsToTheRequestedTimeWindow() {
        segment(10, "A Street", "LINESTRING(13.4 52.5,13.401 52.5)");
        segment(20, "B Street", "LINESTRING(13.5 52.5,13.501 52.5)");
        event(10, 1, "PREFERENCE", 1_500);
        event(20, 2, "PREFERENCE", 1_500);
        event(20, 2, "PREFERENCE", 1_600);
        event(10, 3, "PREFERENCE", 999);
        event(10, 4, "PREFERENCE", 2_001);

        assertThat(rank("PREFERENCE", 1, 1, "")).singleElement()
                .satisfies(row -> assertThat(row.get("street_name")).isEqualTo("B Street"));
    }

    @Test
    void startupIndexScriptIsRepeatableAndBuildsAValidGeographyIndex() {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        assertThat(jdbc.queryForObject("""
                SELECT indisvalid FROM pg_index
                WHERE indexrelid = 'idx_incidents_location_geography'::regclass
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT pg_get_indexdef('idx_incidents_location_geography'::regclass)",
                String.class)).contains("USING gist", "location)::geography", "location IS NOT NULL");
    }

    private List<Map<String, Object>> rank(String type, int minimum, int limit, String intent) {
        return new NamedParameterJdbcTemplate(jdbc).queryForList(rankingSql, Map.of(
                "rank", type, "minRideCount", minimum, "limit", limit,
                "from", 1_000L, "to", 2_000L, "rideIntent", intent));
    }

    private void insertSharedCorridor() {
        segment(10, "Main Street", "LINESTRING(13.4 52.5,13.401 52.5)");
        segment(20, "main street", "LINESTRING(13.401 52.5,13.402 52.5)");
        event(10, 1, "AVOIDANCE", 1_500);
        event(10, 1, "AVOIDANCE", 1_600);
        event(20, 1, "AVOIDANCE", 1_500);
        event(20, 2, "AVOIDANCE", 1_500);
        event(10, 3, "PREFERENCE", 1_500);
        event(10, 3, "PREFERENCE", 1_600);
        event(20, 3, "PREFERENCE", 1_500);
    }

    private void segment(long id, String name, String wkt) {
        jdbc.update("INSERT INTO street_segments VALUES (?, ?, ST_GeomFromText(?,4326))", id, name, wkt);
    }

    private void event(long segment, long ride, String type, long timestamp) {
        jdbc.update("INSERT INTO rides VALUES (?, 'COMMUTE') ON CONFLICT DO NOTHING", new UUID(0, ride));
        jdbc.update("INSERT INTO segment_events VALUES (?, ?, ?, ?, ?, 'COMMUTE')",
                UUID.randomUUID(), segment, new UUID(0, ride), type, timestamp);
    }

    private void incident(long ride, String intent, boolean scary, long timestamp, String wkt) {
        jdbc.update("INSERT INTO rides VALUES (?, ?) ON CONFLICT DO NOTHING", new UUID(0, ride), intent);
        jdbc.update("INSERT INTO incidents VALUES (?, ?, ?, ?, ST_GeomFromText(?,4326))",
                UUID.randomUUID(), new UUID(0, ride), scary, timestamp, wkt);
    }
}

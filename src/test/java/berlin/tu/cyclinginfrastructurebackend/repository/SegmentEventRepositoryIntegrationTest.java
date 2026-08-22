package berlin.tu.cyclinginfrastructurebackend.repository;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class SegmentEventRepositoryIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName
            .parse("postgis/postgis:17-3.4")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("cyclingdb")
            .withUsername("user")
            .withPassword("password")
            .withStartupTimeout(Duration.ofMinutes(2));

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void connect() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void createSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS segment_external_factors");
        jdbcTemplate.execute("DROP TABLE IF EXISTS segment_events");
        jdbcTemplate.execute("""
                CREATE TABLE segment_events (
                    id uuid PRIMARY KEY,
                    segment_id bigint NOT NULL,
                    event_timestamp bigint NOT NULL
                )
                """);
        jdbcTemplate.execute("CREATE INDEX idx_event_segment ON segment_events(segment_id)");
        jdbcTemplate.execute("""
                CREATE TABLE segment_external_factors (
                    id uuid PRIMARY KEY,
                    segment_id bigint NOT NULL,
                    factor_type varchar(50) NOT NULL,
                    source varchar(100) NOT NULL,
                    valid_from bigint NOT NULL,
                    valid_to bigint
                )
                """);
    }

    @Test
    void countsEachEventOnceOnlyWhenAnImportedVizDisruptionIsActive() throws Exception {
        insertEvent(10, 1_000);
        insertEvent(20, 2_000);
        insertEvent(30, 3_000);
        insertEvent(40, 4_000);
        insertEvent(50, 5_000);

        // Two active factors still represent one affected event.
        insertFactor(10, "CONSTRUCTION", "berlin-open-data", 900, 1_100L);
        insertFactor(10, "HAZARD", "berlin-open-data", 1_000, 1_000L);

        // Expired and future factors do not affect the event.
        insertFactor(20, "ROAD_CLOSURE", "berlin-open-data", 1_500, 1_999L);
        insertFactor(20, "EVENT", "berlin-open-data", 2_001, 2_500L);

        // A different source and a non-disruption type are excluded.
        insertFactor(30, "INCIDENT", "another-source", 2_500, 3_500L);
        insertFactor(30, "WEATHER", "berlin-open-data", 2_500, 3_500L);

        // An inclusive start and open-ended validity period are supported.
        insertFactor(40, "INCIDENT", "berlin-open-data", 4_000, null);

        // A factor on another segment must not affect the unmatched event.
        insertFactor(60, "CONSTRUCTION", "berlin-open-data", 0, null);

        Query query = SegmentEventRepository.class
                .getMethod("countRoadDisruptionAffectedEvents")
                .getAnnotation(Query.class);

        assertThat(jdbcTemplate.queryForObject(query.value(), Long.class)).isEqualTo(2L);
    }

    private void insertEvent(long segmentId, long timestamp) {
        jdbcTemplate.update(
                "INSERT INTO segment_events (id, segment_id, event_timestamp) VALUES (?, ?, ?)",
                UUID.randomUUID(), segmentId, timestamp);
    }

    private void insertFactor(long segmentId, String type, String source, long validFrom, Long validTo) {
        jdbcTemplate.update("""
                        INSERT INTO segment_external_factors
                            (id, segment_id, factor_type, source, valid_from, valid_to)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), segmentId, type, source, validFrom, validTo);
    }
}

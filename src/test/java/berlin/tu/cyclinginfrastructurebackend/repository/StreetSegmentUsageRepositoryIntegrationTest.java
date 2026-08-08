package berlin.tu.cyclinginfrastructurebackend.repository;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class StreetSegmentUsageRepositoryIntegrationTest {

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
    private static StreetSegmentUsageRepositoryImpl repository;

    @BeforeAll
    static void connect() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new StreetSegmentUsageRepositoryImpl(jdbcTemplate);
    }

    @BeforeEach
    void createSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS street_segments");
        jdbcTemplate.execute("""
                CREATE TABLE street_segments (
                    id bigint PRIMARY KEY,
                    usage_count integer NOT NULL,
                    avoidance_count integer NOT NULL,
                    preference_count integer NOT NULL,
                    avoidance_ratio double precision,
                    preference_ratio double precision
                )
                """);
    }

    @Test
    void updatesAllCountsAndRatiosInOneStatement() {
        jdbcTemplate.update("""
                INSERT INTO street_segments
                    (id, usage_count, avoidance_count, preference_count)
                VALUES (12, 10, 5, 3), (42, 0, 0, 0)
                """);

        int updated = repository.incrementUsageCounts(new LinkedHashMap<>(Map.of(12L, 2, 42L, 1)));

        assertThat(updated).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT usage_count FROM street_segments WHERE id = 12", Integer.class)).isEqualTo(12);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT avoidance_ratio FROM street_segments WHERE id = 12", Double.class))
                .isCloseTo(5.0 / 17.0, within(0.000_000_1));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT preference_ratio FROM street_segments WHERE id = 12", Double.class))
                .isCloseTo(3.0 / 12.0, within(0.000_000_1));
    }

    @Test
    void reportsMissingSegmentsThroughTheAffectedRowCount() {
        jdbcTemplate.update("""
                INSERT INTO street_segments
                    (id, usage_count, avoidance_count, preference_count)
                VALUES (12, 0, 0, 0)
                """);

        int updated = repository.incrementUsageCounts(Map.of(12L, 1, 42L, 1));

        assertThat(updated).isEqualTo(1);
    }

    @Test
    void emptyInputDoesNotExecuteAnUpdate() {
        assertThat(repository.incrementUsageCounts(Map.of())).isZero();
    }

    @Test
    void overlappingUpdatesWithOppositeInputOrderCompleteWithoutDeadlock() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO street_segments
                    (id, usage_count, avoidance_count, preference_count)
                VALUES (12, 0, 0, 0), (42, 0, 0, 0)
                """);
        Map<Long, Integer> ascending = new LinkedHashMap<>();
        ascending.put(12L, 1);
        ascending.put(42L, 1);
        Map<Long, Integer> descending = new LinkedHashMap<>();
        descending.put(42L, 1);
        descending.put(12L, 1);

        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(
                () -> repository.incrementUsageCounts(ascending));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(
                () -> repository.incrementUsageCounts(descending));

        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT usage_count FROM street_segments ORDER BY id", Integer.class))
                .containsExactly(2, 2);
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}

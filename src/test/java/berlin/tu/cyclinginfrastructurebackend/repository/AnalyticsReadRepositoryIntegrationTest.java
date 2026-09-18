package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AnalyticsReadRepositoryIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName
            .parse("postgis/postgis:17-3.4").asCompatibleSubstituteFor("postgres"));

    private static JdbcTemplate jdbc;
    private static AnalyticsReadRepository repository;

    @BeforeAll
    static void connect() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        repository = new AnalyticsReadRepository(jdbc);
    }

    @BeforeEach
    void createSchema() {
        jdbc.execute("DROP TABLE IF EXISTS rides, segment_events, street_segments");
        jdbc.execute("CREATE TABLE rides (status varchar, route_comparison_type varchar)");
        jdbc.execute("CREATE TABLE street_segments (usage_count bigint, avoidance_count bigint)");
        jdbc.execute("""
                CREATE TABLE segment_events (
                    event_type varchar, event_timestamp bigint,
                    weather_enriched boolean, ohsome_enriched boolean,
                    berlin_open_data_enriched boolean, traffic_enriched boolean,
                    traffic_enrichment_status varchar, ride_intent varchar, traffic_condition varchar
                )
                """);
    }

    @Test
    void emptyTablesReturnZeroCountsNullBoundsAndNoFilterOptions() {
        var rides = repository.rideCounts();
        assertThat(rides.total()).isZero();
        assertThat(rides.statuses()).containsOnlyKeys(
                Arrays.stream(Status.values()).map(Enum::name).toArray(String[]::new));
        assertThat(rides.statuses().values()).allMatch(count -> count == 0);
        assertThat(rides.classifications()).containsOnlyKeys(
                Arrays.stream(RouteComparisonType.values()).map(Enum::name).toArray(String[]::new));
        assertThat(rides.classifications().values()).allMatch(count -> count == 0);
        assertThat(repository.eventCounts()).isEqualTo(new AnalyticsReadRepository.EventCounts(
                0, null, null, 0, 0, 0, 0, 0, 0, 0));
        assertThat(repository.segmentCounts()).isEqualTo(new AnalyticsReadRepository.SegmentCounts(0, 0));
        assertThat(repository.filterOptions().rideIntents()).isEmpty();
        assertThat(repository.filterOptions().trafficConditions()).isEmpty();
    }

    @Test
    void rideHistogramsKeepNullClassificationsInTotalAndIncludeZeroCategories() {
        jdbc.update("""
                INSERT INTO rides VALUES
                ('PROCESSED', 'LOCAL_DETOUR'), ('PROCESSED', 'LOCAL_DETOUR'),
                ('PROCESSED', 'EQUIVALENT_ROUTE'), ('SKIPPED', NULL), ('ERROR', NULL), (NULL, NULL)
                """);
        var result = repository.rideCounts();
        assertThat(result.total()).isEqualTo(6);
        for (Status status : Status.values()) {
            assertThat(result.statuses().get(status.name())).isEqualTo(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rides WHERE status = ?", Long.class, status.name()));
        }
        for (RouteComparisonType type : RouteComparisonType.values()) {
            assertThat(result.classifications().get(type.name())).isEqualTo(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM rides WHERE route_comparison_type = ?", Long.class, type.name()));
        }
    }

    @Test
    void eventAggregatePreservesIndependentEnrichmentFlagsAndMeasuredTrafficMeaning() {
        jdbc.update("""
                INSERT INTO segment_events VALUES
                ('AVOIDANCE', 2000, true, false, true, true, 'ENRICHED', 'COMMUTE', 'LIGHT'),
                ('PREFERENCE', 1000, false, true, false, true, 'NO_DETECTOR_MATCH', 'LEISURE', 'HEAVY'),
                ('PREFERENCE', 3000, true, true, false, false, 'ENRICHED', 'UNKNOWN', 'UNKNOWN'),
                ('AVOIDANCE', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)
                """);
        assertThat(repository.eventCounts()).isEqualTo(new AnalyticsReadRepository.EventCounts(
                4, 1000L, 3000L, 2, 2, 2, 2, 1, 2, 2));
    }

    @Test
    void observedSegmentsRetainTheExistingUsagePlusAvoidancePredicate() {
        jdbc.update("INSERT INTO street_segments VALUES (0,0), (1,0), (0,1), (2,3), (NULL,1)");
        assertThat(repository.segmentCounts()).isEqualTo(new AnalyticsReadRepository.SegmentCounts(5, 3));
    }

    @Test
    void filterOptionsUseObservedCategoriesInFrequencyOrderWithoutStreetData() {
        jdbc.update("""
                INSERT INTO segment_events (ride_intent, traffic_condition) VALUES
                ('COMMUTE', 'LIGHT'), ('COMMUTE', 'LIGHT'), ('COMMUTE', 'LIGHT'),
                ('LEISURE', 'HEAVY'), ('LEISURE', 'HEAVY'), (NULL, NULL)
                """);
        var options = repository.filterOptions();
        assertThat(options.rideIntents()).containsExactly("COMMUTE", "LEISURE", "UNKNOWN");
        assertThat(options.trafficConditions()).containsExactly("LIGHT", "HEAVY");

        // Null and explicit UNKNOWN have the same displayed ride-purpose label;
        // traffic UNKNOWN remains hidden, and absent enum categories are not invented.
        jdbc.update("INSERT INTO segment_events (ride_intent, traffic_condition) VALUES ('UNKNOWN', 'UNKNOWN')");
        assertThat(repository.filterOptions().rideIntents()).containsExactly("COMMUTE", "LEISURE", "UNKNOWN");
        assertThat(repository.filterOptions().trafficConditions()).containsExactly("LIGHT", "HEAVY");
    }
}

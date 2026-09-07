package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayLocation;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayType;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeBatchResult;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeInfrastructureAttributes;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeWorkItem;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeClaim;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeTile;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class OhsomeEnrichmentBatchRepositoryIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName
            .parse("postgis/postgis:17-3.4")
            .asCompatibleSubstituteFor("postgres");
    private static final YearMonth JANUARY = YearMonth.of(2024, 1);

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("cyclingdb")
            .withUsername("user")
            .withPassword("password")
            .withStartupTimeout(Duration.ofMinutes(2));

    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static OhsomeEnrichmentBatchRepository repository;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void connect() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new OhsomeEnrichmentBatchRepository(jdbcTemplate);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS postgis");
    }

    @BeforeEach
    void createSchema() {
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
                    ohsome_processing_status varchar(20) NOT NULL,
                    ohsome_enriched boolean NOT NULL DEFAULT false,
                    surface varchar(255),
                    smoothness varchar(255),
                    lit varchar(255),
                    highway varchar(255),
                    cycleway_type varchar(255),
                    cycleway_location varchar(255),
                    cycleway_surface varchar(255),
                    cycleway_width double precision,
                    bicycle_oneway boolean
                )
                """);
    }

    @Test
    void invalidDataBecomesErrorButOtherCitiesAndYearsRemainPending() {
        insertSegment(10, "LINESTRING(13.2 52.4, 13.3 52.5)");
        insertSegment(20, "LINESTRING(9.99 53.55, 9.991 53.55)");
        insertSegment(30, null);
        insertSegment(40, "LINESTRING EMPTY");
        insertSegment(50, "LINESTRING(190 52, 191 52)");
        insertEvent(1, 10, null);
        insertEvent(2, 10, timestamp(YearMonth.of(2018, 1), 1));
        insertEvent(3, 20, timestamp(YearMonth.of(2026, 3), 1));
        insertEvent(4, 30, timestamp(JANUARY, 1));
        insertEvent(5, 40, timestamp(JANUARY, 1));
        insertEvent(6, 50, timestamp(JANUARY, 1));
        for (long id = 1; id <= 6; id++) putStaleOsmValues(id);

        assertThat(inTransaction(repository::markInvalidPendingEvents)).isEqualTo(4);
        for (long id : new long[]{1, 4, 5, 6}) assertThat(readEvent(id)).isEqualTo(emptyState("ERROR"));
        assertThat(readEvent(2)).isEqualTo(staleState("PENDING"));
        assertThat(readEvent(3)).isEqualTo(staleState("PENDING"));
    }

    @Test
    void separatesBerlinHamburgAndMunichInOneDatabase() {
        insertSegment(10, "LINESTRING(13.40 52.52, 13.401 52.52)");
        insertSegment(20, "LINESTRING(9.99 53.55, 9.991 53.55)");
        insertSegment(30, "LINESTRING(11.57 48.13, 11.571 48.13)");
        for (int i = 1; i <= 3; i++) insertEvent(i, i * 10, timestamp(JANUARY, 1));
        assertThat(inTransaction(repository::markInvalidPendingEvents)).isZero();
        // Deterministic tile ordering, regardless of segment IDs.
        for (long id : new long[]{20, 30, 10}) {
            OhsomeClaim claim = inTransaction(() -> repository.claimNextBatch(0.1, 100)).orElseThrow();
            assertThat(claim.items()).containsExactly(new OhsomeWorkItem(id, JANUARY));
            assertThat(claim.tile()).isEqualTo(switch ((int) id) {
                case 20 -> new OhsomeTile(99, 535);
                case 30 -> new OhsomeTile(115, 481);
                default -> new OhsomeTile(134, 525);
            });
            inTransaction(() -> repository.finalizeBatch(claim.items().stream().map(OhsomeBatchResult::noData).toList()));
        }
        assertThat(repository.hasPendingWork()).isFalse();
    }

    @Test
    void usesExactGridBoundariesAndUtcMonthBoundariesWithoutStudyPeriodLimits() {
        insertSegment(10, "LINESTRING(13.4 52.521, 13.4 52.522)");
        insertSegment(20, "LINESTRING(13.39999999 52.521, 13.39999999 52.522)");
        long boundary = timestamp(YearMonth.of(2026, 2), 1);
        insertEvent(1, 10, boundary);
        insertEvent(2, 20, boundary - 1);
        OhsomeClaim before = inTransaction(() -> repository.claimNextBatch(0.1, 100)).orElseThrow();
        assertThat(before.month()).isEqualTo(YearMonth.of(2026, 1));
        assertThat(before.tile()).isEqualTo(new OhsomeTile(133, 525));
        OhsomeClaim after = inTransaction(() -> repository.claimNextBatch(0.1, 100)).orElseThrow();
        assertThat(after.month()).isEqualTo(YearMonth.of(2026, 2));
        assertThat(after.tile()).isEqualTo(new OhsomeTile(134, 525));
    }

    @Test
    void claimsDistinctSegmentsInTheEarliestMonthAndGroupsDuplicateEvents() {
        insertInsideSegments(10, 20, 30);
        insertEvent(1, 10, timestamp(JANUARY, 5));
        insertEvent(2, 10, timestamp(JANUARY, 20));
        insertEvent(3, 10, timestamp(JANUARY.plusMonths(1), 5));
        insertEvent(4, 20, timestamp(JANUARY, 10));
        insertEvent(5, 30, timestamp(JANUARY, 12));

        List<OhsomeWorkItem> claimed = inTransaction(() ->
                repository.claimNextBatch(0.1, 2).map(OhsomeClaim::items).orElseGet(List::of));

        assertThat(claimed).containsExactly(
                new OhsomeWorkItem(10, JANUARY),
                new OhsomeWorkItem(20, JANUARY));
        assertThat(eventStatuses()).containsExactly(
                "PROCESSING", "PROCESSING", "PENDING", "PROCESSING", "PENDING");
        assertThat(processingSegmentIds()).containsExactly(10L, 20L);
        assertThat(repository.hasPendingWork()).isTrue();
    }

    @Test
    void finalizesMatchedAndNoDataResultsForEveryEventInEachClaimedPair() {
        insertInsideSegments(10, 20, 30);
        insertEvent(1, 10, timestamp(JANUARY, 5));
        insertEvent(2, 10, timestamp(JANUARY, 20));
        insertEvent(3, 20, timestamp(JANUARY, 7));
        insertEvent(4, 20, timestamp(JANUARY, 21));
        insertEvent(5, 30, timestamp(JANUARY, 9));
        for (long id = 1; id <= 5; id++) {
            putStaleOsmValues(id);
        }
        List<OhsomeWorkItem> claimed = inTransaction(() ->
                repository.claimNextBatch(0.1, 2).map(OhsomeClaim::items).orElseGet(List::of));
        OhsomeWorkItem matchedItem = claimed.stream()
                .filter(item -> item.segmentId() == 10)
                .findFirst()
                .orElseThrow();
        OhsomeWorkItem noDataItem = claimed.stream()
                .filter(item -> item.segmentId() == 20)
                .findFirst()
                .orElseThrow();
        OhsomeInfrastructureAttributes attributes = matchedAttributes();

        int updated = inTransaction(() -> repository.finalizeBatch(List.of(
                OhsomeBatchResult.noData(noDataItem),
                OhsomeBatchResult.matched(matchedItem, attributes))));

        assertThat(updated).isEqualTo(4);
        assertThat(readEvent(1)).isEqualTo(matchedState());
        assertThat(readEvent(2)).isEqualTo(matchedState());
        assertThat(readEvent(3)).isEqualTo(emptyState("DONE"));
        assertThat(readEvent(4)).isEqualTo(emptyState("DONE"));
        assertThat(readEvent(5)).isEqualTo(staleState("PENDING"));
        assertThat(repository.processingCounts()).isEqualTo(
                new OhsomeEnrichmentBatchRepository.ProcessingCounts(1, 0, 2, 2, 0));
    }

    @Test
    void releasesEveryEventInTheClaimedPairBackToPending() {
        insertInsideSegments(10, 20);
        insertEvent(1, 10, timestamp(JANUARY, 5));
        insertEvent(2, 10, timestamp(JANUARY, 20));
        insertEvent(3, 20, timestamp(JANUARY, 10));
        List<OhsomeWorkItem> claimed = inTransaction(() ->
                repository.claimNextBatch(0.1, 1).map(OhsomeClaim::items).orElseGet(List::of));

        int released = inTransaction(() -> repository.releaseBatch(claimed));

        assertThat(released).isEqualTo(2);
        assertThat(eventStatuses()).containsExactly("PENDING", "PENDING", "PENDING");
    }

    @Test
    void concurrentClaimsOnlyReturnPairsWhoseEventsTheyTransitioned() throws Exception {
        insertInsideSegments(10);
        insertEvent(1, 10, timestamp(JANUARY, 5));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<List<OhsomeWorkItem>> first;
        CompletableFuture<List<OhsomeWorkItem>> second;
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement statement = blocker.prepareStatement(
                    "SELECT id FROM segment_events WHERE id = 1 FOR UPDATE")) {
                statement.executeQuery().close();
            }

            first = concurrentClaim(ready, start);
            second = concurrentClaim(ready, start);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            try {
                awaitBlockedClaims(2);
            } finally {
                blocker.commit();
            }
        }

        List<OhsomeWorkItem> returned = Stream.concat(
                        first.get(10, TimeUnit.SECONDS).stream(),
                        second.get(10, TimeUnit.SECONDS).stream())
                .toList();
        assertThat(returned).containsExactly(new OhsomeWorkItem(10, JANUARY));
        assertThat(eventStatuses()).containsExactly("PROCESSING");
        assertThat(processingSegmentIds()).containsExactlyElementsOf(
                returned.stream().map(OhsomeWorkItem::segmentId).toList());
    }

    private CompletableFuture<List<OhsomeWorkItem>> concurrentClaim(
            CountDownLatch ready, CountDownLatch start) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try {
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to start concurrent claim");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while starting concurrent claim", exception);
            }
            return inTransaction(() ->
                    repository.claimNextBatch(0.1, 1).map(OhsomeClaim::items).orElseGet(List::of));
        });
    }

    private void awaitBlockedClaims(int expectedCount) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int blocked = 0;
        while (System.nanoTime() < deadline) {
            blocked = Objects.requireNonNull(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)::integer
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND wait_event_type = 'Lock'
                      AND query LIKE '%SET ohsome_processing_status = ''PROCESSING''%'
                    """, Integer.class));
            if (blocked >= expectedCount) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(blocked)
                .as("concurrent claim transactions waiting on the controlled row lock")
                .isGreaterThanOrEqualTo(expectedCount);
    }

    private void insertInsideSegments(long... ids) {
        for (long id : ids) {
            insertSegment(id, "LINESTRING(13.2 52.4, 13.3 52.5)");
        }
    }

    private void insertSegment(long id, String wkt) {
        if (wkt == null) {
            jdbcTemplate.update("INSERT INTO street_segments (id, geometry) VALUES (?, NULL)", id);
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO street_segments (id, geometry)
                VALUES (?, ST_GeomFromText(?, 4326))
                """, id, wkt);
    }

    private void insertEvent(long id, long segmentId, Long timestamp) {
        jdbcTemplate.update("""
                INSERT INTO segment_events (
                    id, segment_id, event_timestamp, ohsome_processing_status, ohsome_enriched
                ) VALUES (?, ?, ?, 'PENDING', false)
                """, id, segmentId, timestamp);
    }

    private void putStaleOsmValues(long id) {
        jdbcTemplate.update("""
                UPDATE segment_events
                SET ohsome_enriched = true,
                    surface = 'old-surface',
                    smoothness = 'old-smoothness',
                    lit = 'old-lit',
                    highway = 'old-highway',
                    cycleway_type = 'LANE',
                    cycleway_location = 'LEFT',
                    cycleway_surface = 'old-cycleway-surface',
                    cycleway_width = 1.25,
                    bicycle_oneway = true
                WHERE id = ?
                """, id);
    }

    private EventState readEvent(long id) {
        return jdbcTemplate.queryForObject("""
                SELECT ohsome_processing_status, ohsome_enriched, surface, smoothness, lit,
                       highway, cycleway_type, cycleway_location, cycleway_surface,
                       cycleway_width, bicycle_oneway
                FROM segment_events
                WHERE id = ?
                """, (resultSet, rowNum) -> new EventState(
                resultSet.getString(1), resultSet.getBoolean(2), resultSet.getString(3),
                resultSet.getString(4), resultSet.getString(5), resultSet.getString(6),
                resultSet.getString(7), resultSet.getString(8), resultSet.getString(9),
                resultSet.getObject(10, Double.class), resultSet.getObject(11, Boolean.class)), id);
    }

    private List<String> eventStatuses() {
        return jdbcTemplate.queryForList(
                "SELECT ohsome_processing_status FROM segment_events ORDER BY id", String.class);
    }

    private List<Long> processingSegmentIds() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT segment_id
                FROM segment_events
                WHERE ohsome_processing_status = 'PROCESSING'
                ORDER BY segment_id
                """, Long.class);
    }

    private static OhsomeInfrastructureAttributes matchedAttributes() {
        return new OhsomeInfrastructureAttributes(
                "asphalt", "excellent", "yes", "cycleway", CyclewayType.TRACK,
                CyclewayLocation.RIGHT, "red-asphalt", 2.5, false);
    }

    private static EventState emptyState(String status) {
        return new EventState(status, false, null, null, null, null, null, null, null, null, null);
    }

    private static EventState staleState(String status) {
        return new EventState(status, true, "old-surface", "old-smoothness", "old-lit", "old-highway",
                "LANE", "LEFT", "old-cycleway-surface", 1.25, true);
    }

    private static EventState matchedState() {
        return new EventState("DONE", true, "asphalt", "excellent", "yes", "cycleway",
                "TRACK", "RIGHT", "red-asphalt", 2.5, false);
    }

    private static long timestamp(YearMonth month, int day) {
        return month.atDay(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    private static long monthStart(YearMonth month) {
        return timestamp(month, 1);
    }

    private static <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }

    private record EventState(
            String status,
            boolean enriched,
            String surface,
            String smoothness,
            String lit,
            String highway,
            String cyclewayType,
            String cyclewayLocation,
            String cyclewaySurface,
            Double cyclewayWidth,
            Boolean bicycleOneway
    ) {}
}

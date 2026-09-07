package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OhsomeSnapshotCacheTest {

    private static final byte[] VALID_SNAPSHOT = "PAR1-test-snapshot-PAR1".getBytes(StandardCharsets.UTF_8);
    private static final OhsomeTile BERLIN = new OhsomeTile(134, 525);
    private static final OhsomeTile HAMBURG = new OhsomeTile(99, 535);
    private static final OhsomeTile MUNICH = new OhsomeTile(115, 481);
    private static final YearMonth JANUARY = YearMonth.of(2019, 1);
    private static final YearMonth FEBRUARY = YearMonth.of(2019, 2);
    private static final Instant TEST_NOW = Instant.parse("2026-08-10T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void downloadsOnlyRequestedTileMonthsAndReusesThemOffline() throws Exception {
        List<String> bodies = new ArrayList<>();
        startServer(exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(exchange.getRequestHeaders().getFirst("authorization")).isEqualTo("test-key");
            respond(exchange, 200, VALID_SNAPSHOT);
        });
        OhsomeV2Properties properties = baseProperties();
        properties.setApiKey("test-key");
        OhsomeSnapshotCache snapshotCache = cache(properties, duration -> { });
        assertThat(bodies).isEmpty();
        var first = snapshotCache.ensureSnapshot(BERLIN, JANUARY);
        assertThat(snapshotCache.ensureSnapshot(BERLIN, JANUARY)).isEqualTo(first);
        snapshotCache.ensureSnapshot(BERLIN, FEBRUARY);
        snapshotCache.ensureSnapshot(HAMBURG, JANUARY);
        snapshotCache.ensureSnapshot(MUNICH, JANUARY);

        assertThat(bodies).hasSize(4);
        List<List<Double>> expected = List.of(BERLIN.bounds(properties), BERLIN.bounds(properties),
                HAMBURG.bounds(properties), MUNICH.bounds(properties));
        for (int i = 0; i < bodies.size(); i++) {
            var body = new ObjectMapper().readTree(bodies.get(i));
            assertThat(body.path("aoi")).isEqualTo(new ObjectMapper().valueToTree(expected.get(i)));
            assertThat(body.path("clip").asBoolean()).isFalse();
            assertThat(body.path("filter").asText()).isEqualTo("type:way and highway=*");
            assertThat(body.has("timestamp")).isFalse();
            assertThat(body.path("time").asText())
                    .isEqualTo(i == 1 ? "2019-02-01T00:00:00Z" : "2019-01-01T00:00:00Z");
        }
        assertThat(Files.readString(properties.getCachePath().resolve("134_525/manifest.json")))
                .contains("first-day-of-month-at-00:00:00Z").doesNotContain("test-key");
        assertThat(properties.getCachePath().resolve("134_525/2019-03.parquet")).doesNotExist();
        server.stop(0);
        server = null;
        properties.setApiKey("");
        var offline = cache(properties, duration -> { throw new AssertionError("No network needed"); });
        assertThat(offline.ensureSnapshot(BERLIN, JANUARY)).isEqualTo(first);
        offline.ensureSnapshot(HAMBURG, JANUARY);
        offline.ensureSnapshot(MUNICH, JANUARY);
    }

    @Test
    void extractionRequestUsesTimeAndRejectsLegacyTimestampField() throws Exception {
        // Reproduce the live v2 API's 422 response to the former request shape.
        startServer(exchange -> {
            var body = new ObjectMapper().readTree(exchange.getRequestBody());
            if (!body.path("time").isTextual() || body.has("timestamp")) {
                respond(exchange, 422, "time is required; timestamp is forbidden".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(exchange, 200, VALID_SNAPSHOT);
        });
        var properties = baseProperties();
        properties.setApiKey("test-key");

        var snapshot = cache(properties, duration -> { }).ensureSnapshot(
                new OhsomeTile(120, 490), YearMonth.of(2020, 7));

        assertThat(snapshot.timestamp()).isEqualTo(Instant.parse("2020-07-01T00:00:00Z"));
        assertThat(snapshot.path()).isRegularFile();
    }

    @Test
    void retriesRateLimitUsingRetryAfter() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            if (requests.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "7");
                respond(exchange, 503, "busy".getBytes(StandardCharsets.UTF_8));
            } else {
                respond(exchange, 200, VALID_SNAPSHOT);
            }
        });

        OhsomeV2Properties properties = baseProperties();
        properties.setApiKey("test-key");
        List<Duration> waits = new ArrayList<>();

        OhsomeCachedSnapshot snapshot = cache(properties, waits::add).ensureSnapshot(BERLIN, JANUARY);

        assertThat(snapshot.month()).isEqualTo(JANUARY);
        assertThat(requests).hasValue(2);
        assertThat(waits).containsExactly(Duration.ofSeconds(7));
    }

    @Test
    void refusesToMixCacheWithDifferentDatasetParameters() throws Exception {
        startServer(exchange -> respond(exchange, 200, VALID_SNAPSHOT));
        OhsomeV2Properties properties = baseProperties();
        properties.setApiKey("test-key");
        cache(properties, duration -> { }).ensureSnapshot(BERLIN, JANUARY);

        properties.setFilter("type:way and highway=cycleway");
        properties.setApiKey("");

        assertThatThrownBy(() -> cache(properties, duration -> { }).ensureSnapshot(BERLIN, JANUARY))
                .isInstanceOf(OhsomeSnapshotCacheException.class)
                .extracting(exception -> ((OhsomeSnapshotCacheException) exception).failureKind())
                .isEqualTo(OhsomeSnapshotCacheException.FailureKind.CONFIGURATION);
    }

    @Test
    void incompleteCacheWithoutApiKeyDoesNotCallTheApiOrLeaveAPartialFile() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, VALID_SNAPSHOT);
        });
        OhsomeV2Properties properties = baseProperties();
        properties.setApiKey("");

        assertThatThrownBy(() -> cache(properties, duration -> { }).ensureSnapshot(BERLIN, JANUARY))
                .isInstanceOf(OhsomeSnapshotCacheException.class)
                .extracting(exception -> ((OhsomeSnapshotCacheException) exception).failureKind())
                .isEqualTo(OhsomeSnapshotCacheException.FailureKind.CONFIGURATION);

        assertThat(requests).hasValue(0);
        assertThat(properties.getCachePath().resolve("134_525/2019-01.parquet")).doesNotExist();
        assertThat(properties.getCachePath().resolve("134_525/staging/2019-01.parquet.part")).doesNotExist();
    }

    @Test
    void accessDeniedIsTerminalForTheCacheInstanceAndDoesNotExposeTheKey() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 401, "unauthorized".getBytes(StandardCharsets.UTF_8));
        });
        OhsomeV2Properties properties = baseProperties();
        properties.setApiKey("secret-test-key");
        OhsomeSnapshotCache snapshotCache = cache(properties, duration -> { });

        assertThatThrownBy(() -> snapshotCache.ensureSnapshot(BERLIN, JANUARY))
                .isInstanceOf(OhsomeSnapshotCacheException.class)
                .satisfies(exception -> {
                    OhsomeSnapshotCacheException cacheException =
                            (OhsomeSnapshotCacheException) exception;
                    assertThat(cacheException.failureKind())
                            .isEqualTo(OhsomeSnapshotCacheException.FailureKind.ACCESS_DENIED);
                    assertThat(cacheException.getMessage()).doesNotContain("secret-test-key");
                });
        assertThatThrownBy(() -> snapshotCache.ensureSnapshot(BERLIN, JANUARY))
                .isInstanceOf(OhsomeSnapshotCacheException.class)
                .extracting(exception -> ((OhsomeSnapshotCacheException) exception).failureKind())
                .isEqualTo(OhsomeSnapshotCacheException.FailureKind.ACCESS_DENIED);

        assertThat(requests).hasValue(1);
        assertThat(properties.getCachePath().resolve("134_525/staging/2019-01.parquet.part")).doesNotExist();
    }

    @Test
    void resumesAfterFailureWithoutRefetchingAnAlreadyCommittedMonth() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            int request = requests.incrementAndGet();
            respond(exchange, request == 2 ? 500 : 200,
                    request == 2 ? "temporary failure".getBytes(StandardCharsets.UTF_8) : VALID_SNAPSHOT);
        });
        OhsomeV2Properties properties = baseProperties();
        properties.setApiKey("test-key");
        properties.setMaxRetries(0);
        var initial = cache(properties, duration -> { });
        initial.ensureSnapshot(BERLIN, JANUARY);
        assertThatThrownBy(() -> initial.ensureSnapshot(BERLIN, FEBRUARY))
                .isInstanceOf(OhsomeSnapshotCacheException.class);
        var restarted = cache(properties, duration -> { });
        restarted.ensureSnapshot(BERLIN, JANUARY);
        restarted.ensureSnapshot(BERLIN, FEBRUARY);
        assertThat(requests).hasValue(3);
        assertThat(properties.getCachePath().resolve("134_525/staging/2019-02.parquet.part")).doesNotExist();
    }

    @Test
    void permanentRejectionDoesNotPoisonOtherTileMonths() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            int request = requests.incrementAndGet();
            respond(exchange, request == 1 ? 400 : 200,
                    request == 1 ? "invalid timestamp".getBytes(StandardCharsets.UTF_8) : VALID_SNAPSHOT);
        });
        var properties = baseProperties();
        properties.setApiKey("test-key");
        var snapshotCache = cache(properties, duration -> { });
        assertThatThrownBy(() -> snapshotCache.ensureSnapshot(BERLIN, YearMonth.of(1900, 1)))
                .isInstanceOf(OhsomeSnapshotCacheException.class);
        assertThat(snapshotCache.ensureSnapshot(HAMBURG, JANUARY).path()).isRegularFile();
        assertThat(requests).hasValue(2);
    }

    @Test
    void changedBufferOrGridCannotReuseTheSameTileCache() throws Exception {
        startServer(exchange -> respond(exchange, 200, VALID_SNAPSHOT));
        var properties = baseProperties();
        properties.setApiKey("test-key");
        cache(properties, duration -> { }).ensureSnapshot(BERLIN, JANUARY);
        properties.setBufferDegrees(0.02);
        assertThatThrownBy(() -> cache(properties, duration -> { }).ensureSnapshot(BERLIN, JANUARY))
                .isInstanceOf(OhsomeSnapshotCacheException.class);
        properties.setBufferDegrees(0.01);
        properties.setGridSizeDegrees(0.05);
        assertThatThrownBy(() -> cache(properties, duration -> { }).ensureSnapshot(BERLIN, JANUARY))
                .isInstanceOf(OhsomeSnapshotCacheException.class);
    }

    @Test
    void corruptedSnapshotIsPreservedAndDownloadedAgain() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> { requests.incrementAndGet(); respond(exchange, 200, VALID_SNAPSHOT); });
        var properties = baseProperties();
        properties.setApiKey("test-key");
        var first = cache(properties, duration -> { }).ensureSnapshot(BERLIN, JANUARY);
        Files.writeString(first.path(), "corrupt");
        cache(properties, duration -> { }).ensureSnapshot(BERLIN, JANUARY);
        assertThat(requests).hasValue(2);
        assertThat(first.path().resolveSibling("2019-01.parquet.invalid")).isRegularFile();
    }

    private OhsomeSnapshotCache cache(OhsomeV2Properties properties, OhsomeSnapshotCache.Delay delay) {
        OhsomeSnapshotValidator validator = path -> {
            if (!java.util.Arrays.equals(Files.readAllBytes(path), VALID_SNAPSHOT)) {
                throw new IOException("not the expected test snapshot");
            }
            return new OhsomeSnapshotMetadata(10, "2.0-test", "1.1.0", "EPSG:4326");
        };
        return new OhsomeSnapshotCache(
                properties,
                validator,
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                Clock.fixed(TEST_NOW, ZoneOffset.UTC),
                delay
        );
    }

    private OhsomeV2Properties baseProperties() {
        OhsomeV2Properties properties = new OhsomeV2Properties();
        properties.setBaseUrl(URI.create("http://localhost:" + server.getAddress().getPort() + "/v2"));
        properties.setCachePath(temporaryDirectory.resolve("cache"));
        properties.setDownloadInterval(Duration.ZERO);
        properties.setInitialRetryDelay(Duration.ZERO);
        return properties;
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v2/extraction/features.parquet", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}

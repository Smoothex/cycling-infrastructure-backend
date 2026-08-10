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
    void defaultDatasetContainsAllSeventyThreeMonthlySnapshots() {
        OhsomeV2Properties properties = new OhsomeV2Properties();

        assertThat(properties.months())
                .hasSize(73)
                .first().isEqualTo(YearMonth.of(2019, 1));
        assertThat(properties.months()).last().isEqualTo(YearMonth.of(2025, 1));
        assertThat(properties.extractionUri().toString())
                .isEqualTo("https://api.heigit.org/ohsome-api-staging/v2/extraction/features.parquet");
        assertThat(properties.supports(YearMonth.of(2019, 1))).isTrue();
        assertThat(properties.supports(YearMonth.of(2025, 2))).isFalse();
        assertThat(properties.containsCoordinate(13.4, 52.5)).isTrue();
        assertThat(properties.containsCoordinate(14.0, 52.5)).isFalse();
    }

    @Test
    void downloadsMissingMonthsThenReusesCompleteCacheWithoutApiKey() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<String> requestBodies = new ArrayList<>();
        startServer(exchange -> {
            requests.incrementAndGet();
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(exchange.getRequestHeaders().getFirst("authorization")).isEqualTo("test-key");
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type"))
                    .isEqualTo("application/json");
            respond(exchange, 200, VALID_SNAPSHOT);
        });

        OhsomeV2Properties properties = propertiesForTwoMonths();
        properties.setApiKey("test-key");
        OhsomeSnapshotCache initialCache = cache(properties, duration -> { });

        OhsomeSnapshotCatalog initialCatalog = initialCache.ensureReady();

        assertThat(requests).hasValue(2);
        assertThat(requestBodies)
                .anySatisfy(body -> assertThat(body).contains("2019-01-01T00:00:00Z"))
                .anySatisfy(body -> assertThat(body).contains("2019-02-01T00:00:00Z"));
        for (String requestBody : requestBodies) {
            var request = new ObjectMapper().readTree(requestBody);
            assertThat(request.path("aoi").toString()).isEqualTo("[12.94,52.24,13.91,52.77]");
            assertThat(request.path("filter").asText()).isEqualTo("type:way and highway=*");
            assertThat(request.path("clip").asBoolean()).isFalse();
        }
        assertThat(initialCatalog.snapshots()).containsOnlyKeys(
                YearMonth.of(2019, 1),
                YearMonth.of(2019, 2)
        );
        assertThat(Files.readString(properties.getCachePath().resolve("manifest.json")))
                .contains("type:way and highway=*")
                .contains("first-day-of-month-at-00:00:00Z")
                .doesNotContain("test-key");

        server.stop(0);
        server = null;
        properties.setApiKey("");
        OhsomeSnapshotCache offlineCache = cache(properties, duration -> {
            throw new AssertionError("An offline cache must not wait for an HTTP request");
        });

        OhsomeSnapshotCatalog offlineCatalog = offlineCache.ensureReady();

        assertThat(offlineCatalog.snapshots()).hasSize(2);
        assertThat(requests).hasValue(2);
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

        OhsomeV2Properties properties = propertiesForOneMonth();
        properties.setApiKey("test-key");
        List<Duration> waits = new ArrayList<>();

        OhsomeSnapshotCatalog catalog = cache(properties, waits::add).ensureReady();

        assertThat(catalog.snapshots()).hasSize(1);
        assertThat(requests).hasValue(2);
        assertThat(waits).containsExactly(Duration.ofSeconds(7));
    }

    @Test
    void refusesToMixCacheWithDifferentDatasetParameters() throws Exception {
        startServer(exchange -> respond(exchange, 200, VALID_SNAPSHOT));
        OhsomeV2Properties properties = propertiesForOneMonth();
        properties.setApiKey("test-key");
        cache(properties, duration -> { }).ensureReady();

        properties.setFilter("type:way and highway=cycleway");
        properties.setApiKey("");

        assertThatThrownBy(() -> cache(properties, duration -> { }).ensureReady())
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
        OhsomeV2Properties properties = propertiesForOneMonth();
        properties.setApiKey("");

        assertThatThrownBy(() -> cache(properties, duration -> { }).ensureReady())
                .isInstanceOf(OhsomeSnapshotCacheException.class)
                .extracting(exception -> ((OhsomeSnapshotCacheException) exception).failureKind())
                .isEqualTo(OhsomeSnapshotCacheException.FailureKind.CONFIGURATION);

        assertThat(requests).hasValue(0);
        assertThat(properties.getCachePath().resolve("snapshots/2019-01.parquet")).doesNotExist();
        assertThat(properties.getCachePath().resolve("staging/2019-01.parquet.part")).doesNotExist();
    }

    @Test
    void accessDeniedIsTerminalForTheCacheInstanceAndDoesNotExposeTheKey() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 401, "unauthorized".getBytes(StandardCharsets.UTF_8));
        });
        OhsomeV2Properties properties = propertiesForOneMonth();
        properties.setApiKey("secret-test-key");
        OhsomeSnapshotCache snapshotCache = cache(properties, duration -> { });

        assertThatThrownBy(snapshotCache::ensureReady)
                .isInstanceOf(OhsomeSnapshotCacheException.class)
                .satisfies(exception -> {
                    OhsomeSnapshotCacheException cacheException =
                            (OhsomeSnapshotCacheException) exception;
                    assertThat(cacheException.failureKind())
                            .isEqualTo(OhsomeSnapshotCacheException.FailureKind.ACCESS_DENIED);
                    assertThat(cacheException.getMessage()).doesNotContain("secret-test-key");
                });
        assertThatThrownBy(snapshotCache::ensureReady)
                .isInstanceOf(OhsomeSnapshotCacheException.class)
                .extracting(exception -> ((OhsomeSnapshotCacheException) exception).failureKind())
                .isEqualTo(OhsomeSnapshotCacheException.FailureKind.ACCESS_DENIED);

        assertThat(requests).hasValue(1);
        assertThat(properties.getCachePath().resolve("staging/2019-01.parquet.part")).doesNotExist();
    }

    @Test
    void resumesAfterFailureWithoutRefetchingAnAlreadyCommittedMonth() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            int request = requests.incrementAndGet();
            respond(exchange, request == 2 ? 500 : 200,
                    request == 2 ? "temporary failure".getBytes(StandardCharsets.UTF_8) : VALID_SNAPSHOT);
        });
        OhsomeV2Properties properties = propertiesForTwoMonths();
        properties.setApiKey("test-key");
        properties.setMaxRetries(0);

        assertThatThrownBy(() -> cache(properties, duration -> { }).ensureReady())
                .isInstanceOf(OhsomeSnapshotCacheException.class)
                .extracting(exception -> ((OhsomeSnapshotCacheException) exception).failureKind())
                .isEqualTo(OhsomeSnapshotCacheException.FailureKind.REMOTE_FAILURE);
        assertThat(properties.getCachePath().resolve("snapshots/2019-01.parquet")).isRegularFile();
        assertThat(properties.getCachePath().resolve("snapshots/2019-02.parquet")).doesNotExist();

        OhsomeSnapshotCatalog catalog = cache(properties, duration -> { }).ensureReady();

        assertThat(catalog.snapshots()).hasSize(2);
        assertThat(requests).hasValue(3);
        assertThat(properties.getCachePath().resolve("staging/2019-02.parquet.part")).doesNotExist();
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

    private OhsomeV2Properties propertiesForTwoMonths() {
        OhsomeV2Properties properties = baseProperties();
        properties.setEndMonth("2019-02");
        return properties;
    }

    private OhsomeV2Properties propertiesForOneMonth() {
        OhsomeV2Properties properties = baseProperties();
        properties.setEndMonth("2019-01");
        return properties;
    }

    private OhsomeV2Properties baseProperties() {
        OhsomeV2Properties properties = new OhsomeV2Properties();
        properties.setBaseUrl(URI.create("http://localhost:" + server.getAddress().getPort() + "/v2"));
        properties.setCachePath(temporaryDirectory.resolve("cache"));
        properties.setStartMonth("2019-01");
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

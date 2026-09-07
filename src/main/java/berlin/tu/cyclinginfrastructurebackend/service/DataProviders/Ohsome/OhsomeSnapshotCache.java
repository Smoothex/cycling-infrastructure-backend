package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeSnapshotCacheException.FailureKind.ACCESS_DENIED;
import static berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeSnapshotCacheException.FailureKind.CONFIGURATION;
import static berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeSnapshotCacheException.FailureKind.INTERRUPTED;
import static berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeSnapshotCacheException.FailureKind.INVALID_REQUEST;
import static berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeSnapshotCacheException.FailureKind.INVALID_SNAPSHOT;
import static berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeSnapshotCacheException.FailureKind.LOCAL_IO;
import static berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeSnapshotCacheException.FailureKind.RATE_LIMITED;
import static berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeSnapshotCacheException.FailureKind.REMOTE_FAILURE;

/** Lazily downloads and validates immutable buffered tile/month snapshots. */
@Service
public class OhsomeSnapshotCache {

    private static final Logger log = LoggerFactory.getLogger(OhsomeSnapshotCache.class);
    private static final int MANIFEST_SCHEMA_VERSION = 2;
    private static final String MANIFEST_FILE = "manifest.json";

    private final OhsomeV2Properties properties;
    private final OhsomeSnapshotValidator validator;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Clock clock;
    private final Delay delay;

    private volatile OhsomeSnapshotCacheException terminalRemoteFailure;
    private Instant lastRequestStartedAt;

    @Autowired
    public OhsomeSnapshotCache(
            OhsomeV2Properties properties,
            OhsomeSnapshotValidator validator
    ) {
        this(
                properties,
                validator,
                new ObjectMapper(),
                HttpClient.newBuilder()
                        .connectTimeout(properties.getConnectTimeout())
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                Clock.systemUTC(),
                OhsomeSnapshotCache::sleepCurrentThread
        );
    }

    OhsomeSnapshotCache(
            OhsomeV2Properties properties,
            OhsomeSnapshotValidator validator,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            Clock clock,
            Delay delay
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.delay = Objects.requireNonNull(delay, "delay");
    }

    /** Only the requested tile/month is acquired; other months remain untouched. */
    public synchronized OhsomeCachedSnapshot ensureSnapshot(OhsomeTile tile, YearMonth month) {
        Objects.requireNonNull(tile, "tile");
        Objects.requireNonNull(month, "month");
        try {
            properties.validate();
        } catch (IllegalArgumentException exception) {
            throw new OhsomeSnapshotCacheException(CONFIGURATION, exception.getMessage(), exception);
        }
        Path root = properties.getCachePath().toAbsolutePath().normalize().resolve(tile.id());
        Path stagingDirectory = root.resolve("staging");
        createDirectories(root, stagingDirectory);
        DatasetDefinition expected = DatasetDefinition.from(properties, tile);
        Manifest manifest = readManifest(root.resolve(MANIFEST_FILE));
        validateManifest(manifest, expected);
        Map<String, ManifestEntry> entries = new LinkedHashMap<>(manifest == null ? Map.of() : manifest.snapshots());
        Path target = root.resolve(month + ".parquet");
        Optional<OhsomeCachedSnapshot> existing = validateExisting(month, target, entries.get(month.toString()));
        if (existing.isPresent()) {
            log.info("Ohsome cache hit: tile={}, bbox={}, month={}", tile.id(), expected.bbox(), month);
            return existing.get();
        }
        if (terminalRemoteFailure != null) {
            throw terminalRemoteFailure;
        }
        if (Files.exists(target)) {
            moveInvalidSnapshot(target);
        }
        requireApiKey(month);
        log.info("Downloading Ohsome snapshot: tile={}, bbox={}, month={}", tile.id(), expected.bbox(), month);
        OhsomeCachedSnapshot downloaded = download(month, expected.bbox(), target, stagingDirectory);
        entries.put(month.toString(), entryFor(downloaded, properties.extractionUri().toString(), clock.instant().toString()));
        writeManifest(root, expected, entries);
        return downloaded;
    }

    private Optional<OhsomeCachedSnapshot> validateExisting(
            YearMonth month,
            Path target,
            ManifestEntry manifestEntry
    ) {
        if (manifestEntry == null || !Files.isRegularFile(target)) {
            return Optional.empty();
        }
        validateEntryIdentity(month, manifestEntry);

        try {
            long size = Files.size(target);
            if (size <= 0) {
                return Optional.empty();
            }
            if (manifestEntry != null && manifestEntry.sizeBytes() != size) {
                log.warn("Cached ohsome snapshot {} has size {}, expected {}", month, size, manifestEntry.sizeBytes());
                return Optional.empty();
            }

            String checksum = sha256(target);
            if (manifestEntry != null && !checksum.equalsIgnoreCase(manifestEntry.sha256())) {
                log.warn("Cached ohsome snapshot {} failed its SHA-256 check", month);
                return Optional.empty();
            }

            OhsomeSnapshotMetadata metadata = validator.validate(target);
            return Optional.of(new OhsomeCachedSnapshot(
                    month,
                    timestamp(month),
                    target,
                    size,
                    checksum,
                    metadata
            ));
        } catch (IOException | RuntimeException exception) {
            log.warn("Cached ohsome snapshot {} is invalid: {}", month, exception.getMessage());
            return Optional.empty();
        }
    }

    private OhsomeCachedSnapshot download(YearMonth month, List<Double> bbox, Path target, Path stagingDirectory) {
        Path partial = stagingDirectory.resolve(month + ".parquet.part");
        deleteIfExists(partial);

        OhsomeSnapshotCacheException lastFailure = null;
        int totalAttempts = properties.getMaxRetries() + 1;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            waitForRequestSlot();
            HttpRequest request = buildRequest(month, bbox);
            HttpResponse<Path> response;
            try {
                lastRequestStartedAt = clock.instant();
                response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofFile(
                                partial,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE
                        )
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new OhsomeSnapshotCacheException(INTERRUPTED,
                        "Interrupted while downloading ohsome snapshot " + month, exception);
            } catch (IOException exception) {
                deleteIfExists(partial);
                lastFailure = new OhsomeSnapshotCacheException(REMOTE_FAILURE,
                        "I/O failure downloading ohsome snapshot " + month, exception);
                if (attempt < totalAttempts) {
                    waitBeforeRetry(attempt, Optional.empty());
                    continue;
                }
                throw lastFailure;
            }

            if (response.statusCode() == 200) {
                try {
                    long size = Files.size(partial);
                    if (size <= 0) {
                        throw new IOException("downloaded file is empty");
                    }
                    OhsomeSnapshotMetadata metadata = validator.validate(partial);
                    String checksum = sha256(partial);
                    atomicMove(partial, target);
                    return new OhsomeCachedSnapshot(month, timestamp(month), target, size, checksum, metadata);
                } catch (IOException | RuntimeException exception) {
                    deleteIfExists(partial);
                    lastFailure = new OhsomeSnapshotCacheException(INVALID_SNAPSHOT,
                            "Downloaded ohsome snapshot " + month + " is invalid", exception);
                    log.warn("Ohsome snapshot validation failed: bbox={}, month={}, attempt={}/{}: {}",
                            bbox, month, attempt, totalAttempts, exception.getMessage());
                    if (attempt < totalAttempts) {
                        waitBeforeRetry(attempt, Optional.empty());
                        continue;
                    }
                    throw lastFailure;
                }
            }

            int status = response.statusCode();
            String responseExcerpt = responseExcerpt(partial);
            deleteIfExists(partial);
            if (status == 401 || status == 403) {
                terminalRemoteFailure = new OhsomeSnapshotCacheException(ACCESS_DENIED,
                        "Ohsome v2 rejected the API key with HTTP " + status + responseExcerpt);
                throw terminalRemoteFailure;
            }
            if (status >= 400 && status < 500 && status != 429 && status != 408) {
                throw new OhsomeSnapshotCacheException(INVALID_REQUEST,
                        "Ohsome v2 rejected the snapshot request with HTTP " + status + responseExcerpt);
            }

            boolean rateLimited = status == 429 || status == 503;
            lastFailure = new OhsomeSnapshotCacheException(rateLimited ? RATE_LIMITED : REMOTE_FAILURE,
                    "Ohsome v2 returned HTTP " + status + " for snapshot " + month + responseExcerpt);
            if (attempt < totalAttempts && (rateLimited || status == 408 || status >= 500)) {
                waitBeforeRetry(attempt, parseRetryAfter(response));
                continue;
            }
            throw lastFailure;
        }

        throw Objects.requireNonNull(lastFailure);
    }

    private HttpRequest buildRequest(YearMonth month, List<Double> bbox) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("aoi", bbox);
        body.put("time", timestamp(month).toString());
        body.put("filter", properties.getFilter());
        body.put("clip", properties.isClip());

        final String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (IOException exception) {
            throw new OhsomeSnapshotCacheException(CONFIGURATION,
                    "Could not encode the ohsome v2 extraction request", exception);
        }

        return HttpRequest.newBuilder(properties.extractionUri())
                .timeout(properties.getRequestTimeout())
                .header("authorization", properties.getApiKey().trim())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
    }

    private void waitForRequestSlot() {
        if (lastRequestStartedAt == null || properties.getDownloadInterval().isZero()) {
            return;
        }
        Instant nextRequestAt = lastRequestStartedAt.plus(properties.getDownloadInterval());
        Duration remaining = Duration.between(clock.instant(), nextRequestAt);
        if (remaining.isPositive()) {
            sleep(remaining, "waiting for the ohsome request interval");
        }
    }

    private void waitBeforeRetry(int failedAttempt, Optional<Duration> retryAfter) {
        Duration backoff = retryAfter.orElseGet(() -> exponentialBackoff(failedAttempt));
        if (backoff.isPositive()) {
            log.warn("Retrying Ohsome download after {} (failed attempt {})", backoff, failedAttempt);
            sleep(backoff, "waiting to retry an ohsome snapshot download");
        }
    }

    private Duration exponentialBackoff(int failedAttempt) {
        Duration delayValue = properties.getInitialRetryDelay();
        for (int multiplier = 1; multiplier < failedAttempt; multiplier++) {
            if (delayValue.compareTo(properties.getMaxRetryDelay().dividedBy(2)) > 0) {
                return properties.getMaxRetryDelay();
            }
            delayValue = delayValue.multipliedBy(2);
        }
        return delayValue.compareTo(properties.getMaxRetryDelay()) > 0
                ? properties.getMaxRetryDelay()
                : delayValue;
    }

    private Optional<Duration> parseRetryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").flatMap(value -> {
            try {
                long seconds = Long.parseLong(value.trim());
                return Optional.of(Duration.ofSeconds(Math.max(0, seconds)));
            } catch (NumberFormatException ignored) {
                // Try the HTTP-date representation below.
            }
            try {
                Instant retryAt = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration duration = Duration.between(clock.instant(), retryAt);
                return Optional.of(duration.isNegative() ? Duration.ZERO : duration);
            } catch (DateTimeParseException ignored) {
                return Optional.empty();
            }
        });
    }

    private void sleep(Duration duration, String operation) {
        try {
            delay.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OhsomeSnapshotCacheException(INTERRUPTED, "Interrupted while " + operation, exception);
        }
    }

    private Manifest readManifest(Path manifestPath) {
        if (!Files.exists(manifestPath)) {
            return null;
        }
        try {
            return objectMapper.readValue(manifestPath.toFile(), Manifest.class);
        } catch (IOException | RuntimeException exception) {
            throw new OhsomeSnapshotCacheException(LOCAL_IO,
                    "Could not read ohsome snapshot manifest " + manifestPath, exception);
        }
    }

    private void validateManifest(Manifest manifest, DatasetDefinition expected) {
        if (manifest == null) {
            return;
        }
        if (manifest.schemaVersion() != MANIFEST_SCHEMA_VERSION) {
            throw new OhsomeSnapshotCacheException(CONFIGURATION,
                    "Unsupported ohsome snapshot manifest schema " + manifest.schemaVersion());
        }
        if (!expected.equals(manifest.dataset())) {
            throw new OhsomeSnapshotCacheException(CONFIGURATION,
                    "The ohsome snapshot manifest describes different dataset parameters");
        }
        for (Map.Entry<String, ManifestEntry> snapshot : manifest.snapshots().entrySet()) {
            ManifestEntry entry = snapshot.getValue();
            if (entry == null
                    || entry.timestamp() == null
                    || entry.filename() == null
                    || entry.sizeBytes() <= 0
                    || entry.sha256() == null
                    || !entry.sha256().matches("(?i)[0-9a-f]{64}")
                    || entry.retrievedAt() == null
                    || entry.sourceUrl() == null
                    || entry.metadata() == null) {
                throw new OhsomeSnapshotCacheException(CONFIGURATION,
                        "The ohsome snapshot manifest has an invalid entry for " + snapshot.getKey());
            }
        }
    }

    private void writeManifest(Path root, DatasetDefinition dataset, Map<String, ManifestEntry> entries) {
        Path partial = root.resolve("staging").resolve("manifest.json.part");
        Path target = root.resolve(MANIFEST_FILE);
        Manifest manifest = new Manifest(
                MANIFEST_SCHEMA_VERSION,
                dataset,
                properties.getBaseUrl().toString(),
                new LinkedHashMap<>(entries)
        );
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(partial.toFile(), manifest);
            atomicMove(partial, target);
        } catch (IOException exception) {
            throw new OhsomeSnapshotCacheException(LOCAL_IO,
                    "Could not write ohsome snapshot manifest " + target, exception);
        }
    }

    private void validateEntryIdentity(YearMonth month, ManifestEntry entry) {
        if (entry == null) {
            return;
        }
        String expectedFilename = month + ".parquet";
        if (!expectedFilename.equals(entry.filename()) || !timestamp(month).toString().equals(entry.timestamp())) {
            throw new OhsomeSnapshotCacheException(CONFIGURATION,
                    "Manifest entry for " + month + " has an unexpected filename or timestamp");
        }
    }

    private ManifestEntry entryFor(OhsomeCachedSnapshot snapshot, String sourceUrl, String retrievedAt) {
        return new ManifestEntry(
                snapshot.timestamp().toString(),
                snapshot.month() + ".parquet",
                snapshot.sizeBytes(),
                snapshot.sha256(),
                retrievedAt,
                sourceUrl,
                snapshot.metadata()
        );
    }

    private void requireApiKey(YearMonth month) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new OhsomeSnapshotCacheException(CONFIGURATION,
                    "Snapshot " + month + " is missing and ohsome.v2.api-key is not configured");
        }
    }

    private void createDirectories(Path snapshotsDirectory, Path stagingDirectory) {
        try {
            Files.createDirectories(snapshotsDirectory);
            Files.createDirectories(stagingDirectory);
        } catch (IOException exception) {
            throw new OhsomeSnapshotCacheException(LOCAL_IO,
                    "Could not create the ohsome snapshot cache directories", exception);
        }
    }

    private void moveInvalidSnapshot(Path target) {
        Path invalid = target.resolveSibling(target.getFileName() + ".invalid");
        int suffix = 1;
        while (Files.exists(invalid)) {
            invalid = target.resolveSibling(target.getFileName() + ".invalid." + suffix++);
        }
        try {
            Files.move(target, invalid);
            log.warn("Preserved invalid ohsome snapshot as {}", invalid);
        } catch (IOException exception) {
            throw new OhsomeSnapshotCacheException(LOCAL_IO,
                    "Could not preserve invalid ohsome snapshot " + target, exception);
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            throw new OhsomeSnapshotCacheException(LOCAL_IO,
                    "Could not remove partial ohsome snapshot " + path, exception);
        }
    }

    private static String sha256(Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String responseExcerpt(Path responseFile) {
        if (!Files.isRegularFile(responseFile)) {
            return "";
        }
        try (InputStream input = Files.newInputStream(responseFile)) {
            String excerpt = new String(input.readNBytes(1_024), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ")
                    .trim();
            return excerpt.isEmpty() ? "" : ": " + excerpt;
        } catch (IOException ignored) {
            return "";
        }
    }

    private static Instant timestamp(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static void sleepCurrentThread(Duration duration) throws InterruptedException {
        long millis = duration.toMillis();
        int nanos = duration.minusMillis(millis).getNano();
        Thread.sleep(millis, nanos);
    }

    @FunctionalInterface
    interface Delay {
        void sleep(Duration duration) throws InterruptedException;
    }

    private record Manifest(
            int schemaVersion,
            DatasetDefinition dataset,
            String apiBaseUrl,
            Map<String, ManifestEntry> snapshots
    ) {
        private Manifest {
            snapshots = snapshots == null ? Map.of() : Map.copyOf(snapshots);
        }
    }

    private record DatasetDefinition(
            double gridSizeDegrees,
            double bufferDegrees,
            List<Double> bbox,
            String filter,
            boolean clip,
            String timestampConvention
    ) {
        private DatasetDefinition {
            bbox = List.copyOf(bbox);
        }

        private static DatasetDefinition from(OhsomeV2Properties properties, OhsomeTile tile) {
            return new DatasetDefinition(
                    properties.getGridSizeDegrees(),
                    properties.getBufferDegrees(),
                    tile.bounds(properties),
                    properties.getFilter(),
                    properties.isClip(),
                    "first-day-of-month-at-00:00:00Z"
            );
        }
    }

    private record ManifestEntry(
            String timestamp,
            String filename,
            long sizeBytes,
            String sha256,
            String retrievedAt,
            String sourceUrl,
            OhsomeSnapshotMetadata metadata
    ) {
    }
}

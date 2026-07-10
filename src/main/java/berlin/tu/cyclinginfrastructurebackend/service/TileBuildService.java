package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.repository.TileExportRepository;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.TileStatusDto;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Builds the static PMTiles tileset consumed by the preference-avoidance map.
 * <p>
 * Pipeline: stream one segment-level GeoJSONSeq export from PostGIS, build two
 * single-layer tilesets from it with strictly non-overlapping zoom ranges, merge them
 * with tile-join, and atomically swap the resulting segments.pmtiles into place. The
 * overview layer intentionally keeps the "streets" layer name for frontend
 * compatibility, but it contains exact segment features so low-zoom colors cannot
 * spread across unrelated or eventless street geometry.
 */
@Service
public class TileBuildService {

    public static final String TILE_FILE_NAME = "segments.pmtiles";

    public enum TileBuildState { IDLE, RUNNING, FAILED }

    private static final Logger log = LoggerFactory.getLogger(TileBuildService.class);

    private static final int STREETS_MIN_ZOOM = 6;
    private static final int STREETS_MAX_ZOOM = 12;
    private static final int SEGMENTS_MIN_ZOOM = 13;
    private static final int SEGMENTS_MAX_ZOOM = 14;

    // Detail tiles at z13 aggregate four z14 tiles and must stay complete; give them
    // headroom beyond tippecanoe's 500K default instead of letting features drop.
    private static final long DETAIL_MAX_TILE_BYTES = 2_000_000L;

    private final TileExportRepository tileExportRepository;
    private final Path tilesDirectory;
    private final String tippecanoeBinary;
    private final String tileJoinBinary;
    private final long buildTimeoutMinutes;

    // tippecanoe/tile-join redraw these in place with \r rather than emitting a new
    // line; BufferedReader.lines() splits on \r too, so each redraw would otherwise
    // become its own INFO line (thousands per build). Demoted to DEBUG.
    private static final Pattern NOISY_PROGRESS_LINE = Pattern.compile(
            "\\d+(\\.\\d+)?%\\s+\\d+/\\d+/\\d+"    // tile-writing progress bar, e.g. "30.7%  4/8/5"
                    + "|Read [\\d.]+ million features"  // feature-read counter
                    + "|Reordering geometry: \\d+%"     // geometry reorder counter
                    + "|\\d+/\\d+/\\d+"                 // bare tile-join z/x/y tick
    );

    // tippecanoe retries several drop percentages per oversized tile before one fits
    // the byte budget, so this fires ~15-20 times per tile within under a second.
    // Only the fact that some tiles needed dropping is useful at INFO; the retry
    // detail goes to DEBUG, and only the first occurrence per build surfaces at INFO.
    private static final Pattern OVERSIZE_TILE_RETRY = Pattern.compile(
            "tile \\d+/\\d+/\\d+ size is \\d+.*with detail"
                    + "|Going to try keeping the sparsest [\\d.]+% of the features"
    );

    private final AtomicBoolean running = new AtomicBoolean(false);
    // set by the analysis/enrichment pipelines whenever they change tile-relevant data;
    // consumed by the scheduled auto-rebuild so tiles never go stale by more than one
    // check interval
    private final AtomicBoolean dataChanged = new AtomicBoolean(false);
    private final ExecutorService buildExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tile-build");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Instant lastGeneratedAt;
    private volatile String lastError;

    public TileBuildService(TileExportRepository tileExportRepository,
                            @Value("${tiles.directory}") String tilesDirectory,
                            @Value("${tiles.tippecanoe-binary}") String tippecanoeBinary,
                            @Value("${tiles.tile-join-binary:tile-join}") String tileJoinBinary,
                            @Value("${tiles.build-timeout-minutes}") long buildTimeoutMinutes) {
        this.tileExportRepository = tileExportRepository;
        this.tilesDirectory = Path.of(tilesDirectory);
        this.tippecanoeBinary = tippecanoeBinary;
        this.tileJoinBinary = tileJoinBinary;
        this.buildTimeoutMinutes = buildTimeoutMinutes;
        initLastGeneratedAtFromExistingFile();
    }

    public Path tileFilePath() {
        return tilesDirectory.resolve(TILE_FILE_NAME);
    }

    /**
     * @return false if a build is already in flight
     */
    public boolean triggerRebuild() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        buildExecutor.submit(this::runBuild);
        return true;
    }

    /**
     * Marks the tileset stale. The scheduled check rebuilds it on its next pass.
     * Called by the pipelines after they change segment counts or enrichment data.
     */
    public void markDataChanged() {
        dataChanged.set(true);
    }

    /**
     * Rebuilds at most once per check interval and only when data actually changed.
     * A change arriving while a build is running stays flagged (the export snapshot
     * predates it) and is picked up by the next pass.
     */
    @Scheduled(fixedDelayString = "${tiles.auto-rebuild-check-ms:300000}")
    void rebuildIfDataChanged() {
        if (!dataChanged.get() || running.get()) {
            return;
        }
        dataChanged.set(false);
        if (triggerRebuild()) {
            log.info("Auto-triggered tile rebuild after pipeline data changes");
        } else {
            // lost the race against a concurrent manual trigger; keep the flag so the
            // change still lands in a rebuild that started after it
            dataChanged.set(true);
        }
    }

    public TileStatusDto getStatus() {
        TileBuildState state = running.get()
                ? TileBuildState.RUNNING
                : (lastError != null ? TileBuildState.FAILED : TileBuildState.IDLE);
        return new TileStatusDto(
                state.name(),
                lastGeneratedAt != null ? lastGeneratedAt.toEpochMilli() : null,
                lastError
        );
    }

    private void runBuild() {
        Path workDirectory = tilesDirectory.resolve("work");
        Path segmentsFile = workDirectory.resolve("segments.geojsonl");
        Path streetsBuild = workDirectory.resolve("streets-build.pmtiles");
        Path segmentsBuild = workDirectory.resolve("segments-build.pmtiles");
        Path outputFile = workDirectory.resolve("merged-build.pmtiles");
        try {
            Files.createDirectories(workDirectory);

            long start = System.currentTimeMillis();
            long segmentCount = export(segmentsFile, tileExportRepository::exportSegmentFeatures);
            log.info("Tile export finished: {} segment features in {} ms",
                    segmentCount, System.currentTimeMillis() - start);

            runTippecanoe(segmentsFile, "streets", STREETS_MIN_ZOOM, STREETS_MAX_ZOOM, null, streetsBuild);
            runTippecanoe(segmentsFile, "segments", SEGMENTS_MIN_ZOOM, SEGMENTS_MAX_ZOOM,
                    DETAIL_MAX_TILE_BYTES, segmentsBuild);
            runTileJoin(streetsBuild, segmentsBuild, outputFile);

            Files.move(outputFile, tileFilePath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            lastGeneratedAt = Instant.now();
            lastError = null;
            log.info("Tile build finished in {} ms -> {}", System.currentTimeMillis() - start, tileFilePath());
        } catch (Exception e) {
            lastError = e.getMessage();
            log.error("Tile build failed", e);
        } finally {
            cleanupQuietly(segmentsFile, streetsBuild, segmentsBuild, outputFile);
            running.set(false);
        }
    }

    private long export(Path file, ExportCall exportCall) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            return exportCall.export(writer);
        }
    }

    private void runTippecanoe(Path inputFile, String layer, int minZoom, int maxZoom,
                               Long maxTileBytes, Path outputFile)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                tippecanoeBinary,
                "--force",
                "-P",
                "-Z", String.valueOf(minZoom),
                "-z", String.valueOf(maxZoom),
                "-l", layer,
                "--drop-densest-as-needed",
                "--coalesce-densest-as-needed"
        ));
        if (maxTileBytes != null) {
            command.add("--maximum-tile-bytes=" + maxTileBytes);
        }
        command.add("-o");
        command.add(outputFile.toString());
        command.add(inputFile.toString());
        runProcess("tippecanoe", command);
    }

    /**
     * -pk keeps tile-join from re-applying the 500K size limit, which would re-drop
     * features from the detail tiles built with the larger byte budget.
     */
    private void runTileJoin(Path streetsBuild, Path segmentsBuild, Path outputFile)
            throws IOException, InterruptedException {
        runProcess("tile-join", List.of(
                tileJoinBinary,
                "--force",
                "-pk",
                "-o", outputFile.toString(),
                streetsBuild.toString(),
                segmentsBuild.toString()
        ));
    }

    private void runProcess(String name, List<String> command) throws IOException, InterruptedException {
        log.info("Running {}", String.join(" ", command));

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        Thread outputDrain = new Thread(() -> {
            boolean[] oversizeWarned = {false};
            try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
                reader.lines().forEach(line -> {
                    if (OVERSIZE_TILE_RETRY.matcher(line).find()) {
                        log.debug("{}: {}", name, line);
                        if (!oversizeWarned[0]) {
                            oversizeWarned[0] = true;
                            log.info("{}: some tiles exceed the size limit, dropping sparse features to fit (see DEBUG for per-tile detail)", name);
                        }
                    } else if (NOISY_PROGRESS_LINE.matcher(line).find()) {
                        log.debug("{}: {}", name, line);
                    } else {
                        log.info("{}: {}", name, line);
                    }
                });
            } catch (IOException e) {
                log.warn("Failed reading {} output", name, e);
            }
        }, name + "-output");
        outputDrain.setDaemon(true);
        outputDrain.start();
        if (!process.waitFor(buildTimeoutMinutes, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException(name + " timed out after " + buildTimeoutMinutes + " minutes");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(name + " exited with code " + process.exitValue());
        }
    }

    private void cleanupQuietly(Path... files) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                log.warn("Could not delete work file {}", file, e);
            }
        }
    }

    private void initLastGeneratedAtFromExistingFile() {
        try {
            Path file = tileFilePath();
            if (Files.exists(file)) {
                lastGeneratedAt = Files.getLastModifiedTime(file).toInstant();
            } else {
                // no tileset yet: let the scheduled check build the first one
                dataChanged.set(true);
            }
        } catch (IOException e) {
            log.warn("Could not read existing tile file timestamp", e);
        }
    }

    @PreDestroy
    void shutdown() {
        buildExecutor.shutdownNow();
    }

    @FunctionalInterface
    private interface ExportCall {
        long export(BufferedWriter writer) throws IOException;
    }
}

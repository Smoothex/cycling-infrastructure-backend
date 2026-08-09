package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.SimRa;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.service.DetourAnalysisResult;
import berlin.tu.cyclinginfrastructurebackend.service.DetourAnalysisService;
import berlin.tu.cyclinginfrastructurebackend.service.MapMatchingService;
import berlin.tu.cyclinginfrastructurebackend.service.ParsedRide;
import berlin.tu.cyclinginfrastructurebackend.service.PipelineActivityTracker;
import berlin.tu.cyclinginfrastructurebackend.service.RideFinalizationService;
import berlin.tu.cyclinginfrastructurebackend.service.RideProcessingResult;
import berlin.tu.cyclinginfrastructurebackend.service.RideTracePoint;
import berlin.tu.cyclinginfrastructurebackend.service.TileBuildService;
import berlin.tu.cyclinginfrastructurebackend.util.ImportMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Component
public class SimRaDataLoader {

    private static final Logger log = LoggerFactory.getLogger(SimRaDataLoader.class);

    private final RideRepository rideRepository;
    private final SimRaFileParser parser;
    private final MapMatchingService mapMatchingService;
    private final DetourAnalysisService detourAnalysisService;
    private final RideFinalizationService rideFinalizationService;
    private final PipelineActivityTracker pipelineActivityTracker;
    private final TileBuildService tileBuildService;
    private final Set<String> attemptedFilesThisRun = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean importComplete = new AtomicBoolean(false);
    private volatile ImportMetrics importMetrics;

    @Value("${simra.data.path:./data/SimRa}")
    private String dataPath;

    @Value("${pipeline.import.enabled:false}")
    private boolean isImportEnabled;

    @Value("${pipeline.enabled:true}")
    private boolean pipelineEnabled;

    @Value("${pipeline.import.batch-size:100}")
    private int importBatchSize;

    @Value("${pipeline.import.thread-pool-size:4}")
    private int importThreadPoolSize;

    public SimRaDataLoader(RideRepository rideRepository,
                           SimRaFileParser parser,
                           MapMatchingService mapMatchingService,
                           DetourAnalysisService detourAnalysisService,
                           RideFinalizationService rideFinalizationService,
                           PipelineActivityTracker pipelineActivityTracker,
                           TileBuildService tileBuildService) {
        this.rideRepository = rideRepository;
        this.parser = parser;
        this.mapMatchingService = mapMatchingService;
        this.detourAnalysisService = detourAnalysisService;
        this.rideFinalizationService = rideFinalizationService;
        this.pipelineActivityTracker = pipelineActivityTracker;
        this.tileBuildService = tileBuildService;
    }

    @Scheduled(fixedDelayString = "${pipeline.import.delay-ms:30000}")
    public void importNextBatch() {
        if (!pipelineEnabled || !isImportEnabled || importComplete.get()) {
            return;
        }

        Path startPath = Paths.get(dataPath);

        if (!Files.exists(startPath)) {
            log.warn("Data path does not exist: {}", dataPath);
            return;
        }
        ImportMetrics metrics = importMetrics();

        Set<String> existingFiles = rideRepository.findAllOriginalFilenames();

        List<Path> filesToProcess;
        int batchLimit = Math.max(1, importBatchSize);
        log.info("Scanning SimRa data path {} for up to {} new files.", dataPath, batchLimit);

        try (Stream<Path> stream = Files.walk(startPath)) {
            filesToProcess = stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> path.toString().contains("Rides"))
                    .filter(path -> path.getFileName().toString().startsWith("VM"))
                    .filter(path -> !existingFiles.contains(path.getFileName().toString()))
                    .filter(path -> !attemptedFilesThisRun.contains(path.getFileName().toString()))
                    .limit(batchLimit)
                    .toList();
        } catch (IOException e) {
            log.error("Error walking file tree", e);
            return;
        }

        if (filesToProcess.isEmpty()) {
            completeImportRun();
            return;
        }
        boolean sourceScanExhausted = filesToProcess.size() < batchLimit;

        try (PipelineActivityTracker.Activity ignored = pipelineActivityTracker.beginWork()) {
            log.info("Starting SimRa import batch with {} files from {}.", filesToProcess.size(), dataPath);
            metrics.recordBatchStarted(filesToProcess.size());
            long segmentUpdateBaseline = metrics.getFinalizationsWithSegmentUpdates();

            int threadCount = Math.max(1, importThreadPoolSize);

            try (ForkJoinPool customThreadPool = new ForkJoinPool(threadCount)) {
                customThreadPool.submit(() ->
                        filesToProcess.parallelStream().forEach(path -> {
                            try {
                                processFile(path, metrics);
                                int current = metrics.getFilesProcessed();
                                if (current > 0 && current % 100 == 0) {
                                    log.info("Committed {} rides in this import run...", current);
                                }
                            } catch (Exception e) {
                                metrics.recordFileFailed();
                                log.error("Failed to process file: {}", path.getFileName(), e);
                            } finally {
                                attemptedFilesThisRun.add(path.getFileName().toString());
                            }
                        })
                ).get();
            } catch (Exception e) {
                log.error("Error during import execution", e);
            }

            log.info("Finished SimRa import batch: {} files attempted, {} rides committed in this run.",
                    filesToProcess.size(), metrics.getFilesProcessed());
            if (metrics.getFinalizationsWithSegmentUpdates() > segmentUpdateBaseline) {
                tileBuildService.markDataChanged();
            }
        }

        if (sourceScanExhausted) {
            completeImportRun();
        }
    }

    private void processFile(Path path, ImportMetrics metrics) {
        String filename = path.getFileName().toString();
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            // 1. Parse
            long parseStart = System.nanoTime();
            ParsedRide parsedRide;
            try {
                parsedRide = parser.parse(fis, filename);
            } catch (IOException e) {
                if (e.getMessage() != null && (e.getMessage().contains("separator not found") || e.getMessage().contains("file is empty"))) {
                    log.debug("Skipping invalid file ({}): {}", e.getMessage(), filename);
                    metrics.recordFileInvalid();
                    return;
                }
                throw e;
            }
            metrics.recordParse(System.nanoTime() - parseStart);

            Ride ride = parsedRide.ride();
            if (parsedRide.trace().isEmpty()) {
                log.debug("Ride has 0 points (skipping): {}", filename);
                metrics.recordFileSkipped();
                return;
            }

            List<RideTracePoint> canonicalTrace = mapMatchingService.canonicalizeTrace(parsedRide.trace());
            if (!isRideInGermany(canonicalTrace)) {
                log.debug("Ride contains points outside Germany (skipping): {}", filename);
                metrics.recordFileSkipped();
                return;
            }

            // 2. Prepare map matching and segment usage without persisting the ride.
            RideProcessingResult result = mapMatchingService.processRide(ride, canonicalTrace);
            metrics.recordProcessing(result);
            if (!result.success()) {
                metrics.recordFileFailed();
                return;
            }

            // 3. Run route comparison inline. All GraphHopper and spatial work stays outside
            // the final write transaction.
            DetourAnalysisResult analysisResult;
            long detourStartedAt = System.nanoTime();
            try {
                analysisResult = detourAnalysisService.analyzeRide(ride, canonicalTrace);
            } finally {
                metrics.recordDetourAnalysis(System.nanoTime() - detourStartedAt);
            }

            // 4. Commit the finalized ride, counters, and events atomically.
            long persistenceStartedAt = System.nanoTime();
            boolean committed = false;
            try {
                rideFinalizationService.finalizeRide(ride, result.usageByEdgeId(), analysisResult);
                committed = true;
            } finally {
                metrics.recordFinalPersistence(
                        System.nanoTime() - persistenceStartedAt,
                        committed && (!result.usageByEdgeId().isEmpty()
                                || !analysisResult.avoidedEdgeBearings().isEmpty()
                                || !analysisResult.chosenEdgeBearings().isEmpty()));
            }
            metrics.recordRideCommitted(ride, result, analysisResult);

        } catch (IOException e) {
            throw new RuntimeException("Error reading file " + filename, e);
        }
    }

    private boolean isRideInGermany(List<RideTracePoint> trace) {
        // Approximate Bounding Box for Germany
        final double MIN_LAT = 47.2;
        final double MAX_LAT = 55.1;
        final double MIN_LON = 5.8;
        final double MAX_LON = 15.1;

        return trace.stream()
                .allMatch(point -> {
                    double lon = point.location().getX();
                    double lat = point.location().getY();
                    return lat >= MIN_LAT && lat <= MAX_LAT && lon >= MIN_LON && lon <= MAX_LON;
                });
    }

    private void completeImportRun() {
        if (!importComplete.compareAndSet(false, true)) {
            return;
        }
        ImportMetrics metrics = importMetrics();
        metrics.finish();
        log.info("No unattempted SimRa ride files remain. Scheduled import scanning is now stopped until restart.");
        metrics.printFinalSummary();
        if (metrics.hasFailures()) {
            log.warn("Some files failed during this run. Restart the backend to make those source files eligible for retry.");
        }
    }

    private synchronized ImportMetrics importMetrics() {
        if (importMetrics == null) {
            importMetrics = new ImportMetrics();
        }
        return importMetrics;
    }
}

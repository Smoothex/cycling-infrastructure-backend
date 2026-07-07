package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.SimRa;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.service.MapMatchingService;
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
import java.util.stream.Stream;

@Component
public class SimRaDataLoader {

    private static final Logger log = LoggerFactory.getLogger(SimRaDataLoader.class);

    private final RideRepository rideRepository;
    private final SimRaFileParser parser;
    private final MapMatchingService mapMatchingService;
    private final Set<String> attemptedFilesThisRun = ConcurrentHashMap.newKeySet();

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
                           MapMatchingService mapMatchingService) {
        this.rideRepository = rideRepository;
        this.parser = parser;
        this.mapMatchingService = mapMatchingService;
    }

    @Scheduled(fixedDelayString = "${pipeline.import.delay-ms:30000}")
    public void importNextBatch() {
        if (!pipelineEnabled || !isImportEnabled) {
            return;
        }

        Path startPath = Paths.get(dataPath);

        if (!Files.exists(startPath)) {
            log.warn("Data path does not exist: {}", dataPath);
            return;
        }

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
            log.debug("No new SimRa files to import.");
            return;
        }

        log.info("Starting SimRa import batch with {} files from {}.", filesToProcess.size(), dataPath);
        ImportMetrics metrics = new ImportMetrics();
        int total = filesToProcess.size();

        int threadCount = Math.max(1, importThreadPoolSize);

        try (ForkJoinPool customThreadPool = new ForkJoinPool(threadCount)) {
            customThreadPool.submit(() ->
                    filesToProcess.parallelStream().forEach(path -> {
                        try {
                            processFile(path, metrics);
                            int current = metrics.getFilesProcessed();
                            if (current > 0 && current % 100 == 0) {
                                log.info("Imported {}/{} rides...", current, total);
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

        metrics.finish();
        metrics.printSummary();
    }

    private void processFile(Path path, ImportMetrics metrics) {
        String filename = path.getFileName().toString();
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            // 1. Parse
            long parseStart = System.nanoTime();
            Ride ride;
            try {
                ride = parser.parse(fis, filename);
            } catch (IOException e) {
                if (e.getMessage() != null && (e.getMessage().contains("separator not found") || e.getMessage().contains("file is empty"))) {
                    log.debug("Skipping invalid file ({}): {}", e.getMessage(), filename);
                    metrics.recordFileInvalid();
                    return;
                }
                throw e;
            }
            metrics.recordParse(System.nanoTime() - parseStart);

            if (ride.getRidePoints().isEmpty()) {
                log.debug("Ride has 0 points (skipping): {}", filename);
                metrics.recordFileSkipped();
                return;
            }

            if (!isRideInGermany(ride)) {
                log.debug("Ride contains points outside Germany (skipping): {}", filename);
                metrics.recordFileSkipped();
                return;
            }

            // 2. Map Match & Persist
            long processingStart = System.nanoTime();
            boolean success = mapMatchingService.processRide(ride);
            metrics.recordMapMatch(System.nanoTime() - processingStart, success);

            if (success) {
                metrics.recordFileProcessed();
            } else {
                metrics.recordFileFailed();
            }

        } catch (IOException e) {
            throw new RuntimeException("Error reading file " + filename, e);
        }
    }

    private boolean isRideInGermany(Ride ride) {
        // Approximate Bounding Box for Germany
        final double MIN_LAT = 47.2;
        final double MAX_LAT = 55.1;
        final double MIN_LON = 5.8;
        final double MAX_LON = 15.1;

        return ride.getRidePoints().stream()
                .filter(p -> p.getLocation() != null)
                .allMatch(p -> {
                    double lon = p.getLocation().getX();
                    double lat = p.getLocation().getY();
                    return lat >= MIN_LAT && lat <= MAX_LAT && lon >= MIN_LON && lon <= MAX_LON;
                });
    }
}

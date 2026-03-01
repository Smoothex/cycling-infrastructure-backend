package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.util.ImportMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;

@Component
public class SimRaDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SimRaDataLoader.class);

    private final RideRepository rideRepository;
    private final SimRaFileParser parser;
    private final MapMatchingService mapMatchingService;

    @Value("${simra.data.path:/Users/momchil.petrov/Downloads/SimRa}")
    private String dataPath;

    @Value("${simra.import.enabled}")
    private boolean isImportEnabled;

    public SimRaDataLoader(RideRepository rideRepository,
                           SimRaFileParser parser,
                           MapMatchingService mapMatchingService) {
        this.rideRepository = rideRepository;
        this.parser = parser;
        this.mapMatchingService = mapMatchingService;
    }

    @Override
    public void run(String... args) {
        if (!isImportEnabled) {
            log.info("SimRa data import is DISABLED via properties. Skipping import phase.");
            return;
        }

        log.info("Starting SimRa data import from: {}", dataPath);
        Path startPath = Paths.get(dataPath);

        if (!Files.exists(startPath)) {
            log.warn("Data path does not exist: {}", dataPath);
            return;
        }

        Set<String> existingFiles = rideRepository.findAllOriginalFilenames();
        log.info("Found {} existing rides in DB.", existingFiles.size());

        List<Path> filesToProcess;
        try (Stream<Path> stream = Files.walk(startPath)) {
            filesToProcess = stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> path.toString().contains("Rides"))
                    .filter(path -> path.getFileName().toString().startsWith("VM"))
                    .filter(path -> !existingFiles.contains(path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            log.error("Error walking file tree", e);
            return;
        }

        log.info("Found {} new files to process.", filesToProcess.size());

        if (filesToProcess.isEmpty()) {
            log.info("No new files to import.");
            return;
        }

        ImportMetrics metrics = new ImportMetrics();
        int total = filesToProcess.size();

        int threadCount = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);

        try (ForkJoinPool customThreadPool = new ForkJoinPool(threadCount)) {
            customThreadPool.submit(() ->
                    filesToProcess.parallelStream().forEach(path -> {
                        try {
                            processFile(path, metrics);
                            int current = metrics.getFilesProcessed();
                            if (current % 100 == 0) {
                                log.info("Imported {}/{} rides...", current, total);
                            }
                        } catch (Exception e) {
                            metrics.recordFileFailed();
                            log.error("Failed to process file: {}", path.getFileName(), e);
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
                    log.warn("Skipping invalid file ({}): {}", e.getMessage(), filename);
                    metrics.recordFileInvalid();
                    return;
                }
                throw e;
            }
            metrics.recordParse(System.nanoTime() - parseStart);

            if (ride.getRidePoints().isEmpty()) {
                log.warn("Ride has 0 points (skipping): {}", filename);
                metrics.recordFileSkipped();
                return;
            }

            if (!isRideInGermany(ride)) {
                log.warn("Ride contains points outside Germany (skipping): {}", filename);
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
package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.util.ImportMetrics;
import berlin.tu.cyclinginfrastructurebackend.util.RideAnalyzer;
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
    private final GraphHopperMapMatchingService mapMatchingService;

    @Value("${simra.data.path:/Users/momchil.petrov/Downloads/SimRa}")
    private String dataPath;

    private final int threadCount = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    private final ForkJoinPool customThreadPool = new ForkJoinPool(threadCount);

    public SimRaDataLoader(RideRepository rideRepository, SimRaFileParser parser,
                           GraphHopperMapMatchingService mapMatchingService) {
        this.rideRepository = rideRepository;
        this.parser = parser;
        this.mapMatchingService = mapMatchingService;
    }

    @Override
    public void run(String... args) {
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

        // Submit to custom pool instead of common pool
        try {
            customThreadPool.submit(() ->
                filesToProcess.parallelStream().forEach(path -> {
                    try {
                        processFile(path, metrics);
                        int current = metrics.getFilesProcessed();
                        if (current % 500 == 0) {
                            log.info("Imported {}/{} rides...", current, total);
                        }
                    } catch (Exception e) {
                        metrics.recordFileFailed();
                        log.error("Failed to process file: " + path.getFileName(), e);
                    }
                })
            ).get(); // Wait for completion
        } catch (Exception e) {
            log.error("Error during import execution", e);
        } finally {
            customThreadPool.shutdown();
        }

        metrics.finish();
        metrics.printSummary();

        log.info("SimRa data import completed.");
    }

    private void processFile(Path path, ImportMetrics metrics) {
        String filename = path.getFileName().toString();
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            // Parse
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

            // DB Save
            long dbStart = System.nanoTime();
            Ride savedRide = rideRepository.save(ride);
            metrics.recordDbSave(System.nanoTime() - dbStart);

            // Map Match
            long mapMatchStart = System.nanoTime();
            boolean mapMatchSuccess = mapMatchingService.mapMatch(savedRide);
            metrics.recordMapMatch(System.nanoTime() - mapMatchStart, mapMatchSuccess);

            metrics.recordFileProcessed();

        } catch (IOException e) {
            throw new RuntimeException("Error reading file " + filename, e);
        }
    }

    private boolean isRideInGermany(Ride ride) {
        if (ride.getRidePoints() == null || ride.getRidePoints().isEmpty()) {
            return false;
        }

        // Approximate Bounding Box for Germany
        final double MIN_LAT = 47.2;
        final double MAX_LAT = 55.1;
        final double MIN_LON = 5.8;
        final double MAX_LON = 15.1;

        for (var point : ride.getRidePoints()) {
            if (point.getLocation() != null) {
                // JTS Point: X - Longitude, Y - Latitude
                double lon = point.getLocation().getX();
                double lat = point.getLocation().getY();

                if (lat < MIN_LAT || lat > MAX_LAT || lon < MIN_LON || lon > MAX_LON) {
                    return false; // Found a point outside Germany
                }
            }
        }
        return true;
    }

    /**
     * Analyzes a single ride file for map matching issues.
     * Usage: ./gradlew bootRun --args="--analyze=/path/to/VM2_-64940635"
     */
    private void runAnalysis(Path path) {
        log.info("Running ride analysis for: {}", path);

        if (!Files.exists(path)) {
            log.error("File not found: {}", path);
            return;
        }

        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            String filename = path.getFileName().toString();
            Ride ride = parser.parse(fis, filename);

            RideAnalyzer.AnalysisResult result = RideAnalyzer.analyze(ride, filename);
            RideAnalyzer.printReport(result);

        } catch (IOException e) {
            log.error("Failed to analyze file: {}", path, e);
        }
    }

    /**
     * Analyzes a single ride file and outputs GeoJSON for visualization.
     * Usage: ./gradlew bootRun --args="--analyze-geojson=/path/to/VM2_-64940635"
     * Paste the output into https://geojson.io to visualize
     */
    private void runAnalysisWithGeoJson(Path path) {
        log.info("Running ride analysis with GeoJSON for: {}", path);

        if (!Files.exists(path)) {
            log.error("File not found: {}", path);
            return;
        }

        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            String filename = path.getFileName().toString();
            Ride ride = parser.parse(fis, filename);

            RideAnalyzer.AnalysisResult result = RideAnalyzer.analyze(ride, filename);
            RideAnalyzer.printReport(result);

            System.out.println("\n--- GeoJSON (paste into https://geojson.io) ---\n");
            System.out.println(RideAnalyzer.toGeoJson(ride));

        } catch (IOException e) {
            log.error("Failed to analyze file: {}", path, e);
        }
    }
}

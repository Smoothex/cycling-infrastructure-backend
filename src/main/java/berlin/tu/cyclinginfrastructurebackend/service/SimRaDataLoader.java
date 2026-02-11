package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class SimRaDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SimRaDataLoader.class);

    private final RideRepository rideRepository;
    private final SimRaFileParser parser;

    @Value("${simra.data.path:/Users/momchil.petrov/Downloads/SimRa}")
    private String dataPath;

    // Use a custom ForkJoinPool to limit concurrency and avoid exhausting resources (DB, Memory)
    // Adjust parallelism based on available cores/DB pool size.
    // Hikari default is 10. Let's be conservative to avoid starvation.
    private final ForkJoinPool customThreadPool = new ForkJoinPool(5);

    public SimRaDataLoader(RideRepository rideRepository, SimRaFileParser parser) {
        this.rideRepository = rideRepository;
        this.parser = parser;
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
        log.info("Found {} existing rides in DB. Skipping them.", existingFiles.size());

        List<Path> filesToProcess;
        try (Stream<Path> stream = Files.walk(startPath)) {
            filesToProcess = stream.filter(Files::isRegularFile)
                .filter(path -> !path.getFileName().toString().startsWith("."))
                .filter(path -> path.toString().contains("Rides"))
                .filter(path -> path.getFileName().toString().startsWith("VM"))
                .filter(path -> !existingFiles.contains(path.getFileName().toString()))
                .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Error walking file tree", e);
            return;
        }

        log.info("Found {} new files to process.", filesToProcess.size());

        AtomicInteger count = new AtomicInteger(0);
        int total = filesToProcess.size();

        // Submit to custom pool instead of common pool
        try {
            customThreadPool.submit(() ->
                filesToProcess.parallelStream().forEach(path -> {
                    try {
                        processFile(path);
                        int current = count.incrementAndGet();
                        if (current % 500 == 0) {
                            log.info("Imported {}/{} rides...", current, total);
                        }
                    } catch (Exception e) {
                        log.error("Failed to process file: " + path.getFileName(), e);
                    }
                })
            ).get(); // Wait for completion
        } catch (Exception e) {
            log.error("Error during import execution", e);
        } finally {
            customThreadPool.shutdown();
        }

        log.info("SimRa data import completed.");
    }

    private void processFile(Path path) {
        String filename = path.getFileName().toString();
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            Ride ride = parser.parse(fis, filename);

            if (ride.getRidePoints().isEmpty()) {
                log.warn("Ride has 0 points (skipping): {}", filename);
                return;
            }

            // Save individual ride (transactional by default)
            rideRepository.save(ride);

        } catch (IOException e) {
            throw new RuntimeException("Error reading file " + filename, e);
        }
    }
}

package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class DetourAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(DetourAnalysisScheduler.class);

    private final RideRepository rideRepository;
    private final DetourAnalysisService detourAnalysisService;
    private final Executor analysisExecutor;

    @Value("${analysis.batch.enabled:false}")
    private boolean isBatchEnabled;

    @Value("${analysis.batch.size}")
    private int batchSize;

    public DetourAnalysisScheduler(RideRepository rideRepository,
                                   DetourAnalysisService detourAnalysisService,
                                   @Qualifier("analysisExecutor") Executor analysisExecutor) {
        this.rideRepository = rideRepository;
        this.detourAnalysisService = detourAnalysisService;
        this.analysisExecutor = analysisExecutor;
    }

    /**
     * Fetches batches of ride IDs, submits each ride
     * to the thread pool in parallel, awaits the batch, then repeats until
     * no PENDING rides remain.
     */
    @Scheduled(fixedDelayString = "${analysis.batch.delay.ms}")
    public void processPendingRides() {
        if (!isBatchEnabled) {
            return;
        }

        long totalPending = rideRepository.countByStatus(Status.PENDING);
        if (totalPending == 0) {
            log.debug("No PENDING rides found for analysis.");
            return;
        }

        log.info("=== Analysis run started. Total PENDING rides: {} ===", totalPending);
        Instant runStart = Instant.now();

        AtomicInteger totalProcessed = new AtomicInteger();
        AtomicInteger totalErrors = new AtomicInteger();
        int batchNumber = 0;

        while (true) {
            List<UUID> rideIds = rideRepository.findIdsByStatus(
                    Status.PENDING, PageRequest.of(0, batchSize));

            if (rideIds.isEmpty()) {
                break;
            }

            batchNumber++;
            Instant batchStart = Instant.now();
            log.info("Batch #{}: submitting {} rides to thread pool...", batchNumber, rideIds.size());

            // Submit all rides in this batch to the executor in parallel
            List<CompletableFuture<Status>> futures = rideIds.stream()
                    .map(id -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return detourAnalysisService.analyzeRide(id);
                        } catch (Exception e) {
                            log.error("Uncaught error analyzing ride {}", id, e);
                            return Status.ERROR;
                        }
                    }, analysisExecutor))
                    .toList();

            // Await all futures in this batch before fetching the next
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            // Monitor
            int batchSuccess = 0;
            int batchErrors = 0;
            for (CompletableFuture<Status> f : futures) {
                Status result = f.join();
                if (result == Status.PROCESSED) {
                    batchSuccess++;
                } else {
                    batchErrors++;
                }
            }

            totalProcessed.addAndGet(batchSuccess);
            totalErrors.addAndGet(batchErrors);

            Duration batchDuration = Duration.between(batchStart, Instant.now());
            double ridesPerSec = rideIds.size() / Math.max(batchDuration.toMillis() / 1000.0, 0.001);
            int remaining = (int) totalPending - totalProcessed.get() - totalErrors.get();

            log.info("Batch #{} done in {}s — success: {}, errors: {} | "
                            + "Overall progress: {}/{} ({}%) | {} rides/sec | ~{} remaining",
                    batchNumber,
                    batchDuration.toSeconds(),
                    batchSuccess,
                    batchErrors,
                    totalProcessed.get() + totalErrors.get(),
                    totalPending,
                    String.format("%.1f", (totalProcessed.get() + totalErrors.get()) * 100.0 / totalPending),
                    String.format("%.0f", ridesPerSec),
                    Math.max(remaining, 0));
        }

        Duration totalDuration = Duration.between(runStart, Instant.now());
        log.info("=== Analysis run complete. {} processed, {} errors in {} ({} batches) ===",
                totalProcessed.get(), totalErrors.get(), formatDuration(totalDuration), batchNumber);
    }

    private String formatDuration(Duration d) {
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        long seconds = d.toSecondsPart();
        if (hours > 0) return String.format("%dh %dm %ds", hours, minutes, seconds);
        if (minutes > 0) return String.format("%dm %ds", minutes, seconds);
        return String.format("%ds", seconds);
    }
}
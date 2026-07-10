package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class DetourAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(DetourAnalysisScheduler.class);

    private final RideRepository rideRepository;
    private final DetourAnalysisService detourAnalysisService;
    private final Executor analysisExecutor;
    private final PipelineWorkClaimService workClaimService;
    private final TileBuildService tileBuildService;

    @Value("${pipeline.enabled:true}")
    private boolean pipelineEnabled;

    @Value("${pipeline.analysis.enabled:false}")
    private boolean isBatchEnabled;

    @Value("${pipeline.analysis.batch-size:500}")
    private int batchSize;

    @Value("${pipeline.analysis.delay-ms:10000}")
    private long delayMs;

    @Value("${pipeline.analysis.thread-pool-size:8}")
    private int threadPoolSize;

    @Value("${pipeline.analysis.progress-log-interval-ms:30000}")
    private long progressLogIntervalMs;

    public DetourAnalysisScheduler(RideRepository rideRepository,
                                   DetourAnalysisService detourAnalysisService,
                                   @Qualifier("analysisExecutor") Executor analysisExecutor,
                                   PipelineWorkClaimService workClaimService,
                                   TileBuildService tileBuildService) {
        this.rideRepository = rideRepository;
        this.detourAnalysisService = detourAnalysisService;
        this.analysisExecutor = analysisExecutor;
        this.workClaimService = workClaimService;
        this.tileBuildService = tileBuildService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logSchedulerConfiguration() {
        if (!pipelineEnabled || !isBatchEnabled) {
            log.info("Detour analysis is DISABLED (pipeline.enabled={}, pipeline.analysis.enabled={})",
                    pipelineEnabled, isBatchEnabled);
            return;
        }
        long pendingRides = rideRepository.countByStatus(Status.PENDING);
        log.info("Detour analysis is ENABLED: batches of up to {} rides every {}s on {} threads | {} rides pending",
                batchSize, delayMs / 1000, threadPoolSize, pendingRides);
    }

    /**
     * Claims one bounded batch of PENDING rides, submits the claimed rides
     * to the thread pool in parallel, then returns control to the scheduler.
     */
    @Scheduled(fixedDelayString = "${pipeline.analysis.delay-ms:10000}")
    public void processPendingRides() {
        if (!pipelineEnabled || !isBatchEnabled) {
            return;
        }

        List<UUID> rideIds = workClaimService.claimPendingRidesForAnalysis(batchSize);
        if (rideIds.isEmpty()) {
            log.debug("No rides claimed for analysis.");
            return;
        }

        long pendingAfterClaim = rideRepository.countByStatus(Status.PENDING);
        log.info("=== Detour analysis batch started: {} rides claimed, {} still pending ===",
                rideIds.size(), pendingAfterClaim);

        Instant runStart = Instant.now();
        AtomicInteger completedCount = new AtomicInteger();
        Map<Status, AtomicInteger> statusCounts = new ConcurrentHashMap<>();

        List<CompletableFuture<Void>> futures = rideIds.stream()
                .map(id -> CompletableFuture.runAsync(() -> {
                    Status result;
                    try {
                        result = detourAnalysisService.analyzeRide(id);
                    } catch (Exception e) {
                        log.error("Uncaught error analyzing ride {}", id, e);
                        rideRepository.updateStatus(id, Status.ERROR);
                        result = Status.ERROR;
                    }
                    statusCounts.computeIfAbsent(result, s -> new AtomicInteger()).incrementAndGet();
                    completedCount.incrementAndGet();
                }, analysisExecutor))
                .toList();

        awaitWithProgressLogging(
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)),
                completedCount, statusCounts, rideIds.size(), runStart);

        Duration totalDuration = Duration.between(runStart, Instant.now());
        double ridesPerSec = completedCount.get() / Math.max(totalDuration.toMillis() / 1000.0, 0.001);
        long pendingAfterBatch = rideRepository.countByStatus(Status.PENDING);
        log.info("=== Detour analysis batch finished: {} processed, {} alternative routes, {} skipped, {} errors "
                        + "in {} ({} rides/sec) | {} rides pending ===",
                count(statusCounts, Status.PROCESSED),
                count(statusCounts, Status.ALTERNATIVE_ROUTE),
                count(statusCounts, Status.SKIPPED),
                count(statusCounts, Status.ERROR),
                formatDuration(totalDuration),
                String.format("%.1f", ridesPerSec),
                pendingAfterBatch);

        if (completedCount.get() > 0) {
            tileBuildService.markDataChanged();
        }
    }

    /**
     * Blocks until all analysis tasks finish, emitting a progress log line
     * every {@code progressLogIntervalMs} so long batches are visible in the logs.
     */
    private void awaitWithProgressLogging(CompletableFuture<Void> allTasks,
                                          AtomicInteger completedCount,
                                          Map<Status, AtomicInteger> statusCounts,
                                          int total,
                                          Instant runStart) {
        while (true) {
            try {
                allTasks.get(progressLogIntervalMs, TimeUnit.MILLISECONDS);
                return;
            } catch (TimeoutException e) {
                logProgress(completedCount.get(), total, statusCounts, runStart);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for analysis batch to finish ({}/{} rides done).",
                        completedCount.get(), total);
                return;
            } catch (ExecutionException e) {
                // tasks catch their own exceptions, so this should not happen
                log.error("Unexpected failure while waiting for analysis batch", e.getCause());
                return;
            }
        }
    }

    private void logProgress(int done, int total, Map<Status, AtomicInteger> statusCounts, Instant runStart) {
        double elapsedSec = Math.max(Duration.between(runStart, Instant.now()).toMillis() / 1000.0, 0.001);
        double ridesPerSec = done / elapsedSec;
        String eta = ridesPerSec > 0
                ? formatDuration(Duration.ofSeconds((long) ((total - done) / ridesPerSec)))
                : "unknown";
        log.info("Detour analysis progress: {}/{} rides done ({} errors) | {} rides/sec, ~{} remaining",
                done, total, count(statusCounts, Status.ERROR), String.format("%.1f", ridesPerSec), eta);
    }

    private int count(Map<Status, AtomicInteger> statusCounts, Status status) {
        AtomicInteger counter = statusCounts.get(status);
        return counter != null ? counter.get() : 0;
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

package berlin.tu.cyclinginfrastructurebackend.util;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RideIntent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.service.DetourAnalysisResult;
import berlin.tu.cyclinginfrastructurebackend.service.RideProcessingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe metrics collector for tracking import performance.
 * Provides aggregate summary statistics for one complete import run.
 */
public class ImportMetrics {

    private static final Logger log = LoggerFactory.getLogger(ImportMetrics.class);

    private final long startTimeNanos;
    private volatile long endTimeNanos;

    // Counters
    private final AtomicInteger filesProcessed = new AtomicInteger(0);
    private final AtomicInteger filesSkipped = new AtomicInteger(0);
    private final AtomicInteger filesInvalid = new AtomicInteger(0);
    private final AtomicInteger filesFailed = new AtomicInteger(0);
    private final AtomicInteger mapMatchSucceeded = new AtomicInteger(0);
    private final AtomicInteger mapMatchFailed = new AtomicInteger(0);
    private final AtomicInteger processedRides = new AtomicInteger(0);
    private final AtomicInteger skippedRides = new AtomicInteger(0);
    private final AtomicInteger batchesProcessed = new AtomicInteger(0);
    private final AtomicInteger filesDiscovered = new AtomicInteger(0);
    private final AtomicLong usageObservations = new AtomicLong(0);
    private final AtomicLong distinctUsageUpdates = new AtomicLong(0);
    private final AtomicLong avoidanceEvents = new AtomicLong(0);
    private final AtomicLong preferenceEvents = new AtomicLong(0);
    private final AtomicLong equivalentRoutes = new AtomicLong(0);
    private final AtomicLong localDetours = new AtomicLong(0);
    private final AtomicLong corridorAlternatives = new AtomicLong(0);
    private final AtomicLong routesWithoutComparison = new AtomicLong(0);
    private final AtomicLong commuteRides = new AtomicLong(0);
    private final AtomicLong leisureRides = new AtomicLong(0);
    private final AtomicLong unknownIntentRides = new AtomicLong(0);
    private final AtomicLong importedIncidents = new AtomicLong(0);

    private final TimingMetric parsing = new TimingMetric();
    private final TimingMetric totalProcessing = new TimingMetric();
    private final TimingMetric graphHopper = new TimingMetric();
    private final TimingMetric timestampCalculation = new TimingMetric();
    private final TimingMetric segmentPreparation = new TimingMetric();
    private final TimingMetric detourAnalysis = new TimingMetric();
    private final TimingMetric finalPersistence = new TimingMetric();
    private final AtomicInteger finalizationsWithSegmentUpdates = new AtomicInteger();

    public ImportMetrics() {
        this.startTimeNanos = System.nanoTime();
    }

    public void recordParse(long durationNanos) {
        parsing.record(durationNanos);
    }

    public void recordBatchStarted(int fileCount) {
        batchesProcessed.incrementAndGet();
        filesDiscovered.addAndGet(fileCount);
    }

    public void recordProcessing(RideProcessingResult result) {
        totalProcessing.record(result.totalProcessingNanos());
        graphHopper.recordIfMeasured(result.graphHopperNanos());
        timestampCalculation.recordIfMeasured(result.timestampCalculationNanos());
        segmentPreparation.recordIfMeasured(result.segmentPreparationNanos());
        if (result.success()) {
            mapMatchSucceeded.incrementAndGet();
        } else {
            mapMatchFailed.incrementAndGet();
        }
    }

    public boolean hasSegmentUpdates() {
        return finalizationsWithSegmentUpdates.get() > 0;
    }

    public long getFinalizationsWithSegmentUpdates() {
        return finalizationsWithSegmentUpdates.get();
    }

    public void recordDetourAnalysis(long durationNanos) {
        detourAnalysis.record(durationNanos);
    }

    public void recordFinalPersistence(long durationNanos, boolean hasSegmentUpdates) {
        finalPersistence.record(durationNanos);
        if (hasSegmentUpdates) {
            finalizationsWithSegmentUpdates.incrementAndGet();
        }
    }

    public void recordRideCommitted(Ride ride,
                                    RideProcessingResult processingResult,
                                    DetourAnalysisResult analysisResult) {
        Status status = ride.getStatus();
        if (status == Status.PROCESSED) {
            processedRides.incrementAndGet();
        } else if (status == Status.SKIPPED) {
            skippedRides.incrementAndGet();
        } else {
            throw new IllegalArgumentException("Committed ride has non-final status " + status);
        }
        filesProcessed.incrementAndGet();
        distinctUsageUpdates.addAndGet(processingResult.usageByEdgeId().size());
        usageObservations.addAndGet(processingResult.usageByEdgeId().values().stream()
                .mapToLong(Integer::longValue)
                .sum());
        avoidanceEvents.addAndGet(analysisResult.avoidedEdgeBearings().size());
        preferenceEvents.addAndGet(analysisResult.chosenEdgeBearings().size());
        recordRouteComparison(ride.getRouteComparisonType());
        recordRideIntent(ride.getRideIntent());
        importedIncidents.addAndGet(ride.getIncidents().size());
    }

    public void recordFileSkipped() {
        filesSkipped.incrementAndGet();
    }

    public void recordFileInvalid() {
        filesInvalid.incrementAndGet();
    }

    public void recordFileFailed() {
        filesFailed.incrementAndGet();
    }

    public void finish() {
        this.endTimeNanos = System.nanoTime();
    }

    public int getFilesProcessed() {
        return filesProcessed.get();
    }

    public boolean hasFailures() {
        return filesFailed.get() > 0;
    }

    /**
     * Prints a comprehensive summary of the import metrics.
     */
    public void printFinalSummary() {
        if (endTimeNanos == 0) {
            finish();
        }

        long totalElapsedNanos = endTimeNanos - startTimeNanos;
        int committed = filesProcessed.get();
        int rejected = filesSkipped.get() + filesInvalid.get();
        int attempted = committed + rejected + filesFailed.get();
        long totalEvents = avoidanceEvents.get() + preferenceEvents.get();

        log.info("═══════════════════════════════════════════════════════════════════");
        log.info("                 FINAL SIMRA IMPORT SUMMARY                         ");
        log.info("═══════════════════════════════════════════════════════════════════");
        log.info("Result:                 {}",
                filesFailed.get() == 0 ? "COMPLETED" : "COMPLETED WITH FAILURES");
        log.info("Total Duration:         {}", formatDuration(totalElapsedNanos));
        log.info("Batches Processed:      {}", batchesProcessed.get());
        log.info("───────────────────────────────────────────────────────────────────");
        log.info("SOURCE FILES:");
        log.info("  Selected:             {}", filesDiscovered.get());
        log.info("  Attempted:            {}", attempted);
        log.info("  Rejected (validation): {}", filesSkipped.get());
        log.info("  Invalid format:       {}", filesInvalid.get());
        log.info("  Failed:               {}", filesFailed.get());
        log.info("───────────────────────────────────────────────────────────────────");
        log.info("COMMITTED RIDES:");
        log.info("  Total:                {}", committed);
        log.info("  PROCESSED:            {}", processedRides.get());
        log.info("  SKIPPED:              {}", skippedRides.get());
        log.info("───────────────────────────────────────────────────────────────────");
        log.info("ROUTE ANALYSIS:");
        log.info("  EQUIVALENT_ROUTE:     {}", equivalentRoutes.get());
        log.info("  LOCAL_DETOUR:         {}", localDetours.get());
        log.info("  CORRIDOR_ALTERNATIVE: {}", corridorAlternatives.get());
        log.info("  No comparison:        {}", routesWithoutComparison.get());
        log.info("  COMMUTE intent:       {}", commuteRides.get());
        log.info("  LEISURE intent:       {}", leisureRides.get());
        log.info("  UNKNOWN intent:       {}", unknownIntentRides.get());
        log.info("  Safety incidents:     {}", importedIncidents.get());
        log.info("───────────────────────────────────────────────────────────────────");
        log.info("ANALYTICAL OUTPUT:");
        log.info("  Usage observations:   {}", usageObservations.get());
        log.info("  Usage segment updates: {}", distinctUsageUpdates.get());
        log.info("  Segment events:       {}", totalEvents);
        log.info("    AVOIDANCE:          {}", avoidanceEvents.get());
        log.info("    PREFERENCE:         {}", preferenceEvents.get());
        log.info("───────────────────────────────────────────────────────────────────");
        log.info("Map Match Success:      {}", mapMatchSucceeded.get());
        log.info("Map Match Failed:       {}", mapMatchFailed.get());
        int processingAttempts = mapMatchSucceeded.get() + mapMatchFailed.get();
        log.info("Map Match Success Rate: {}",
                processingAttempts > 0
                        ? String.format("%.1f%%", (mapMatchSucceeded.get() * 100.0) / processingAttempts)
                        : "N/A");
        log.info("───────────────────────────────────────────────────────────────────");
        log.info("TIMING BREAKDOWN:");
        logTiming("Parsing", parsing);
        logTiming("Total processing", totalProcessing);
        logTiming("GraphHopper", graphHopper);
        logTiming("Timestamp calculation", timestampCalculation);
        logTiming("Segment preparation", segmentPreparation);
        logTiming("Inline detour analysis", detourAnalysis);
        logTiming("Final persistence", finalPersistence);
        log.info("───────────────────────────────────────────────────────────────────");

        if (committed > 0) {
            double ridesPerSecond = committed / (totalElapsedNanos / 1_000_000_000.0);
            log.info("Throughput:             {} committed rides/second",
                    String.format("%.2f", ridesPerSecond));
        }
        log.info("═══════════════════════════════════════════════════════════════════");
    }

    private void recordRouteComparison(RouteComparisonType comparisonType) {
        if (comparisonType == null) {
            routesWithoutComparison.incrementAndGet();
            return;
        }
        switch (comparisonType) {
            case EQUIVALENT_ROUTE -> equivalentRoutes.incrementAndGet();
            case LOCAL_DETOUR -> localDetours.incrementAndGet();
            case CORRIDOR_ALTERNATIVE -> corridorAlternatives.incrementAndGet();
        }
    }

    private void recordRideIntent(RideIntent rideIntent) {
        if (rideIntent == null || rideIntent == RideIntent.UNKNOWN) {
            unknownIntentRides.incrementAndGet();
        } else if (rideIntent == RideIntent.COMMUTE) {
            commuteRides.incrementAndGet();
        } else if (rideIntent == RideIntent.LEISURE) {
            leisureRides.incrementAndGet();
        }
    }

    private void logTiming(String label, TimingMetric metric) {
        String average = metric.sampleCount() > 0
                ? formatDuration(metric.totalNanos() / metric.sampleCount())
                : "N/A";
        log.info("  {}: total={}  avg={}  max={}  samples={}",
                label,
                formatDuration(metric.totalNanos()),
                average,
                formatDuration(metric.maxNanos()),
                metric.sampleCount());
    }

    /**
     * Formats a duration in nanoseconds into a human-readable unit for summary logging.
     *
     * @param nanos the duration in nanoseconds
     * @return the formatted duration string
     */
    private String formatDuration(long nanos) {
        if (nanos < 1_000) {
            return nanos + "ns";
        } else if (nanos < 1_000_000) {
            return String.format("%.2fµs", nanos / 1_000.0);
        } else if (nanos < 1_000_000_000) {
            return String.format("%.2fms", nanos / 1_000_000.0);
        } else {
            Duration duration = Duration.ofNanos(nanos);
            long hours = duration.toHours();
            long minutes = duration.toMinutesPart();
            long seconds = duration.toSecondsPart();
            long millis = duration.toMillisPart();

            if (hours > 0) {
                return String.format("%dh %dm %ds", hours, minutes, seconds);
            } else if (minutes > 0) {
                return String.format("%dm %ds", minutes, seconds);
            } else {
                return String.format("%.2fs", seconds + millis / 1000.0);
            }
        }
    }

    private static final class TimingMetric {
        private final AtomicLong totalNanos = new AtomicLong();
        private final AtomicLong maxNanos = new AtomicLong();
        private final AtomicLong sampleCount = new AtomicLong();

        private void record(long durationNanos) {
            if (durationNanos < 0) {
                throw new IllegalArgumentException("Duration cannot be negative");
            }
            totalNanos.addAndGet(durationNanos);
            maxNanos.accumulateAndGet(durationNanos, Math::max);
            sampleCount.incrementAndGet();
        }

        private void recordIfMeasured(long durationNanos) {
            if (durationNanos > 0) {
                record(durationNanos);
            }
        }

        private long totalNanos() {
            return totalNanos.get();
        }

        private long maxNanos() {
            return maxNanos.get();
        }

        private long sampleCount() {
            return sampleCount.get();
        }
    }
}

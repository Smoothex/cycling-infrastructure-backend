package berlin.tu.cyclinginfrastructurebackend.util;

import berlin.tu.cyclinginfrastructurebackend.service.RideProcessingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe metrics collector for tracking import performance.
 * Provides summary statistics at the end of a batch import.
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

    private final TimingMetric parsing = new TimingMetric();
    private final TimingMetric totalProcessing = new TimingMetric();
    private final TimingMetric graphHopper = new TimingMetric();
    private final TimingMetric timestampCalculation = new TimingMetric();
    private final TimingMetric segmentUpdates = new TimingMetric();
    private final TimingMetric ridePersistence = new TimingMetric();

    public ImportMetrics() {
        this.startTimeNanos = System.nanoTime();
    }

    public void recordParse(long durationNanos) {
        parsing.record(durationNanos);
    }

    public void recordProcessing(RideProcessingResult result) {
        totalProcessing.record(result.totalProcessingNanos());
        graphHopper.recordIfMeasured(result.graphHopperNanos());
        timestampCalculation.recordIfMeasured(result.timestampCalculationNanos());
        segmentUpdates.recordIfMeasured(result.segmentUpdateNanos());
        ridePersistence.recordIfMeasured(result.ridePersistenceNanos());
        if (result.success()) {
            mapMatchSucceeded.incrementAndGet();
        } else {
            mapMatchFailed.incrementAndGet();
        }
    }

    public boolean hasSegmentUpdates() {
        return segmentUpdates.sampleCount() > 0;
    }

    public void recordFileProcessed() {
        filesProcessed.incrementAndGet();
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

    /**
     * Prints a comprehensive summary of the import metrics.
     */
    public void printSummary() {
        if (endTimeNanos == 0) {
            finish();
        }

        long totalElapsedNanos = endTimeNanos - startTimeNanos;
        int processed = filesProcessed.get();

        log.info("═══════════════════════════════════════════════════════════════════");
        log.info("                     IMPORT SUMMARY                                 ");
        log.info("═══════════════════════════════════════════════════════════════════");
        log.info("Total Duration:         {}", formatDuration(totalElapsedNanos));
        log.info("───────────────────────────────────────────────────────────────────");
        log.info("Files Processed:        {}", processed);
        log.info("Files Skipped (0 pts):  {}", filesSkipped.get());
        log.info("Files Invalid:          {}", filesInvalid.get());
        log.info("Files Failed:           {}", filesFailed.get());
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
        logTiming("Segment updates", segmentUpdates);
        logTiming("Ride persistence", ridePersistence);
        log.info("───────────────────────────────────────────────────────────────────");

        if (processed > 0) {
            double filesPerSecond = processed / (totalElapsedNanos / 1_000_000_000.0);
            log.info("Throughput:             {} files/second", String.format("%.2f", filesPerSecond));
        }
        log.info("═══════════════════════════════════════════════════════════════════");
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

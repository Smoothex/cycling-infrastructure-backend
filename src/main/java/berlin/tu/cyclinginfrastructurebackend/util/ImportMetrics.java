package berlin.tu.cyclinginfrastructurebackend.util;

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

    // Timing accumulators (in nanoseconds)
    private final AtomicLong totalParseTimeNanos = new AtomicLong(0);
    private final AtomicLong totalDbSaveTimeNanos = new AtomicLong(0);
    private final AtomicLong totalMapMatchTimeNanos = new AtomicLong(0);

    // Min/max tracking
    private final AtomicLong maxParseTimeNanos = new AtomicLong(0);
    private final AtomicLong maxDbSaveTimeNanos = new AtomicLong(0);
    private final AtomicLong maxMapMatchTimeNanos = new AtomicLong(0);

    public ImportMetrics() {
        this.startTimeNanos = System.nanoTime();
    }

    public void recordParse(long durationNanos) {
        totalParseTimeNanos.addAndGet(durationNanos);
        updateMax(maxParseTimeNanos, durationNanos);
    }

    public void recordDbSave(long durationNanos) {
        totalDbSaveTimeNanos.addAndGet(durationNanos);
        updateMax(maxDbSaveTimeNanos, durationNanos);
    }

    public void recordMapMatch(long durationNanos, boolean success) {
        totalMapMatchTimeNanos.addAndGet(durationNanos);
        updateMax(maxMapMatchTimeNanos, durationNanos);
        if (success) {
            mapMatchSucceeded.incrementAndGet();
        } else {
            mapMatchFailed.incrementAndGet();
        }
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
        log.info("Map Match Success Rate: {}%",
                processed > 0 ? String.format("%.1f", (mapMatchSucceeded.get() * 100.0) / processed) : "N/A");
        log.info("───────────────────────────────────────────────────────────────────");
        log.info("TIMING BREAKDOWN:");
        log.info("  Parsing:      total={}  avg={}  max={}",
                formatDuration(totalParseTimeNanos.get()),
                processed > 0 ? formatDuration(totalParseTimeNanos.get() / processed) : "N/A",
                formatDuration(maxParseTimeNanos.get()));
        log.info("  DB Save:      total={}  avg={}  max={}",
                formatDuration(totalDbSaveTimeNanos.get()),
                processed > 0 ? formatDuration(totalDbSaveTimeNanos.get() / processed) : "N/A",
                formatDuration(maxDbSaveTimeNanos.get()));
        log.info("  Map Match:    total={}  avg={}  max={}",
                formatDuration(totalMapMatchTimeNanos.get()),
                processed > 0 ? formatDuration(totalMapMatchTimeNanos.get() / processed) : "N/A",
                formatDuration(maxMapMatchTimeNanos.get()));
        log.info("───────────────────────────────────────────────────────────────────");

        if (processed > 0) {
            double filesPerSecond = processed / (totalElapsedNanos / 1_000_000_000.0);
            log.info("Throughput:             {} files/second", String.format("%.2f", filesPerSecond));
        }
        log.info("═══════════════════════════════════════════════════════════════════");
    }

    /**
     * Updates a shared maximum value using a compare-and-set loop so concurrent writers do not lose
     * a larger measurement.
     *
     * @param max the shared maximum holder
     * @param newValue the candidate value
     */
    private void updateMax(AtomicLong max, long newValue) {
        long current;
        do {
            current = max.get();
            if (newValue <= current) return;
        } while (!max.compareAndSet(current, newValue));
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
}

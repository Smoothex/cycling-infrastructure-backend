package berlin.tu.cyclinginfrastructurebackend.util;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RideIntent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.service.DetourAnalysisResult;
import berlin.tu.cyclinginfrastructurebackend.service.RideProcessingResult;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImportMetricsTest {

    @Test
    void reportsEachProcessingPhaseSeparately() {
        Logger logger = (Logger) LoggerFactory.getLogger(ImportMetrics.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            ImportMetrics metrics = new ImportMetrics();
            metrics.recordBatchStarted(2);
            metrics.recordParse(500);
            RideProcessingResult processingResult = new RideProcessingResult(
                    true, Map.of(42L, 1), 6_000, 1_000, 2_000, 3_000);
            metrics.recordProcessing(processingResult);
            metrics.recordDetourAnalysis(4_000);
            metrics.recordFinalPersistence(5_000, true);
            DetourAnalysisResult analysisResult = new DetourAnalysisResult(
                    Map.of(12, 180.0), Map.of(12, 1_000L),
                    Map.of(42, 90.0), Map.of(42, 2_000L));
            Ride processedRide = new Ride();
            processedRide.setStatus(Status.PROCESSED);
            processedRide.setRouteComparisonType(RouteComparisonType.LOCAL_DETOUR);
            processedRide.setRideIntent(RideIntent.COMMUTE);
            metrics.recordRideCommitted(processedRide, processingResult, analysisResult);
            RideProcessingResult skippedResult = new RideProcessingResult(
                    true, Map.of(), 500, 0, 0, 0);
            metrics.recordProcessing(skippedResult);
            Ride skippedRide = new Ride();
            skippedRide.setStatus(Status.SKIPPED);
            skippedRide.setRideIntent(RideIntent.UNKNOWN);
            metrics.recordRideCommitted(skippedRide, skippedResult, DetourAnalysisResult.empty());
            metrics.finish();
            metrics.printFinalSummary();

            String output = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(output)
                    .contains("FINAL SIMRA IMPORT SUMMARY")
                    .contains("SOURCE FILES:")
                    .contains("COMMITTED RIDES:")
                    .contains("Total:                2")
                    .contains("PROCESSED:            1")
                    .contains("SKIPPED:              1")
                    .contains("ROUTE ANALYSIS:")
                    .contains("LOCAL_DETOUR:         1")
                    .contains("No comparison:        1")
                    .contains("COMMUTE intent:       1")
                    .contains("UNKNOWN intent:       1")
                    .contains("ANALYTICAL OUTPUT:")
                    .contains("Segment events:       2")
                    .contains("AVOIDANCE:          1")
                    .contains("PREFERENCE:         1")
                    .contains("Parsing:")
                    .contains("Total processing:")
                    .contains("GraphHopper:")
                    .contains("Timestamp calculation:")
                    .contains("Segment preparation:")
                    .contains("Inline detour analysis:")
                    .contains("Final persistence:");
            assertThat(metrics.hasSegmentUpdates()).isTrue();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}

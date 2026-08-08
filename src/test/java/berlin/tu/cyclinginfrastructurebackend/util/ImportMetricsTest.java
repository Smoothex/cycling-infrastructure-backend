package berlin.tu.cyclinginfrastructurebackend.util;

import berlin.tu.cyclinginfrastructurebackend.service.RideProcessingResult;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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
            metrics.recordParse(500);
            metrics.recordProcessing(new RideProcessingResult(true, 6_000, 1_000, 2_000, 3_000, 4_000));
            metrics.recordFileProcessed();
            metrics.finish();
            metrics.printSummary();

            String output = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertThat(output)
                    .contains("Parsing:")
                    .contains("Total processing:")
                    .contains("GraphHopper:")
                    .contains("Timestamp calculation:")
                    .contains("Segment updates:")
                    .contains("Ride persistence:");
            assertThat(metrics.hasSegmentUpdates()).isTrue();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}

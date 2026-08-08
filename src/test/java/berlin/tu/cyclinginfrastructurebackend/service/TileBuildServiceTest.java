package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.repository.TileExportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TileBuildServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void waitsForBothPipelineIdleTimeAndTheQuietPeriod() {
        PipelineActivityTrackerTest.MutableClock clock = new PipelineActivityTrackerTest.MutableClock(
                Instant.parse("2026-08-09T10:00:00Z"));
        PipelineActivityTracker tracker = new PipelineActivityTracker(clock);
        TileBuildService service = service(tracker, clock, true);

        assertThat(service.isAutoRebuildDue(Instant.parse("2026-08-09T10:14:59Z"))).isFalse();
        assertThat(service.isAutoRebuildDue(Instant.parse("2026-08-09T10:15:00Z"))).isTrue();

        PipelineActivityTracker.Activity activity = tracker.beginWork();
        assertThat(service.isAutoRebuildDue(Instant.parse("2026-08-09T11:00:00Z"))).isFalse();
        clock.set(Instant.parse("2026-08-09T11:00:00Z"));
        activity.close();

        assertThat(service.isAutoRebuildDue(Instant.parse("2026-08-09T11:14:59Z"))).isFalse();
        assertThat(service.isAutoRebuildDue(Instant.parse("2026-08-09T11:15:00Z"))).isTrue();
    }

    @Test
    void disabledAutomaticBuildsRemainDisabledWhenDataIsStale() {
        PipelineActivityTrackerTest.MutableClock clock = new PipelineActivityTrackerTest.MutableClock(
                Instant.parse("2026-08-09T10:00:00Z"));
        PipelineActivityTracker tracker = new PipelineActivityTracker(clock);
        TileBuildService service = service(tracker, clock, false);

        assertThat(service.isAutoRebuildDue(Instant.parse("2026-08-10T10:00:00Z"))).isFalse();
    }

    private TileBuildService service(PipelineActivityTracker tracker,
                                     PipelineActivityTrackerTest.MutableClock clock,
                                     boolean enabled) {
        return new TileBuildService(
                mock(TileExportRepository.class),
                tracker,
                tempDirectory.toString(),
                "tippecanoe",
                "tile-join",
                30,
                enabled,
                900_000,
                clock
        );
    }
}

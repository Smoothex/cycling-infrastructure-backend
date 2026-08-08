package berlin.tu.cyclinginfrastructurebackend.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineActivityTrackerTest {

    @Test
    void tracksOverlappingWorkAndRecordsWhenThePipelineBecomesIdle() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-09T10:00:00Z"));
        PipelineActivityTracker tracker = new PipelineActivityTracker(clock);

        PipelineActivityTracker.Activity first = tracker.beginWork();
        PipelineActivityTracker.Activity second = tracker.beginWork();
        assertThat(tracker.isActive()).isTrue();

        clock.set(Instant.parse("2026-08-09T10:05:00Z"));
        first.close();
        assertThat(tracker.isActive()).isTrue();

        clock.set(Instant.parse("2026-08-09T10:10:00Z"));
        second.close();
        second.close();

        assertThat(tracker.isActive()).isFalse();
        assertThat(tracker.idleSince()).isEqualTo(Instant.parse("2026-08-09T10:10:00Z"));
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

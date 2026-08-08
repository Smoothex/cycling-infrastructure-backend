package berlin.tu.cyclinginfrastructurebackend.service;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class PipelineActivityTracker {

    private final AtomicInteger activeWorkCount = new AtomicInteger();
    private final AtomicReference<Instant> idleSince;
    private final Clock clock;

    public PipelineActivityTracker() {
        this(Clock.systemUTC());
    }

    PipelineActivityTracker(Clock clock) {
        this.clock = clock;
        this.idleSince = new AtomicReference<>(clock.instant());
    }

    public Activity beginWork() {
        activeWorkCount.incrementAndGet();
        return new Activity(this);
    }

    public boolean isActive() {
        return activeWorkCount.get() > 0;
    }

    public Instant idleSince() {
        return idleSince.get();
    }

    private void endWork() {
        int remaining = activeWorkCount.decrementAndGet();
        if (remaining < 0) {
            activeWorkCount.incrementAndGet();
            throw new IllegalStateException("Pipeline activity count became negative");
        }
        if (remaining == 0) {
            idleSince.set(clock.instant());
        }
    }

    public static final class Activity implements AutoCloseable {
        private final PipelineActivityTracker tracker;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Activity(PipelineActivityTracker tracker) {
            this.tracker = tracker;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                tracker.endWork();
            }
        }
    }
}

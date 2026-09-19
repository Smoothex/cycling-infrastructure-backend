package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeSnapshotCacheException.FailureKind.LOCAL_IO;
import static berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeSnapshotCacheException.FailureKind.QUOTA_EXCEEDED;

/** Persistent, single-backend request budget. Cache reads do not consume requests. */
final class OhsomeRequestBudget {
    private static final Duration WINDOW = Duration.ofHours(24);
    private static final Duration QUOTA_RETRY_DELAY = Duration.ofHours(8);
    private final OhsomeV2Properties properties;
    private final Clock clock;
    private final ObjectMapper mapper;
    private State state;

    OhsomeRequestBudget(OhsomeV2Properties properties, Clock clock, ObjectMapper mapper) {
        this.properties = properties;
        this.clock = clock;
        this.mapper = mapper;
    }

    synchronized Optional<Instant> resumeAt() {
        load();
        long now = clock.millis();
        state.requests().removeIf(start -> start <= now - WINDOW.toMillis());
        long resume = state.blockedUntil();
        if (state.requests().size() >= properties.getMaxRequestsPerDay()) {
            // If the configured budget was lowered, enough older requests must expire.
            int index = state.requests().size() - properties.getMaxRequestsPerDay();
            resume = Math.max(resume, state.requests().get(index) + WINDOW.toMillis());
        }
        return resume > now ? Optional.of(Instant.ofEpochMilli(resume)) : Optional.empty();
    }

    synchronized Optional<Instant> lastRequestAt() {
        load();
        return state.requests().isEmpty() ? Optional.empty()
                : Optional.of(Instant.ofEpochMilli(state.requests().getLast()));
    }

    /** Record before sending: retries and failed requests also consume the local budget. */
    synchronized void reserve() {
        resumeAt().ifPresent(at -> { throw paused(at); });
        state.requests().add(clock.millis());
        save();
    }

    synchronized Instant quotaExceeded(Optional<Duration> retryAfter) {
        load();
        // Without a provider reset hint, probe again after eight hours.
        Duration wait = retryAfter.filter(Duration::isPositive).orElse(QUOTA_RETRY_DELAY);
        state = new State(state.requests(), clock.instant().plus(wait).toEpochMilli());
        save();
        return resumeAt().orElseThrow();
    }

    static OhsomeSnapshotCacheException paused(Instant resumeAt) {
        return new OhsomeSnapshotCacheException(QUOTA_EXCEEDED,
                "Ohsome request quota paused; automatic retry at " + resumeAt);
    }

    private Path path() {
        return properties.getCachePath().resolve("request-budget.json");
    }

    private void load() {
        if (state != null) return;
        try {
            State loaded = Files.exists(path()) ? mapper.readValue(path().toFile(), State.class)
                    : new State(new ArrayList<>(), 0);
            if (loaded.requests() == null || loaded.blockedUntil() < 0
                    || loaded.requests().stream().anyMatch(value -> value == null || value < 0)) {
                throw new IOException("Invalid request budget state");
            }
            state = new State(new ArrayList<>(loaded.requests().stream().sorted().toList()), loaded.blockedUntil());
        } catch (IOException | RuntimeException exception) {
            throw new OhsomeSnapshotCacheException(LOCAL_IO,
                    "Could not read ohsome request budget " + path(), exception);
        }
    }

    private void save() {
        Path partial = path().resolveSibling("request-budget.json.part");
        try {
            Files.createDirectories(path().getParent());
            mapper.writeValue(partial.toFile(), state);
            try {
                Files.move(partial, path(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(partial, path(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new OhsomeSnapshotCacheException(LOCAL_IO,
                    "Could not persist ohsome request budget " + path(), exception);
        }
    }

    private record State(List<Long> requests, long blockedUntil) { }
}

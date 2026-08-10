package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Objects;

public record OhsomeCachedSnapshot(
        YearMonth month,
        Instant timestamp,
        Path path,
        long sizeBytes,
        String sha256,
        OhsomeSnapshotMetadata metadata
) {
    public OhsomeCachedSnapshot {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(metadata, "metadata");
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive");
        }
    }
}

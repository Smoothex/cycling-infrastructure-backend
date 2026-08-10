package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import java.time.YearMonth;
import java.util.Map;
import java.util.Objects;

public record OhsomeSnapshotCatalog(Map<YearMonth, OhsomeCachedSnapshot> snapshots) {
    public OhsomeSnapshotCatalog {
        snapshots = Map.copyOf(Objects.requireNonNull(snapshots, "snapshots"));
    }

    public OhsomeCachedSnapshot snapshot(YearMonth month) {
        OhsomeCachedSnapshot snapshot = snapshots.get(Objects.requireNonNull(month, "month"));
        if (snapshot == null) {
            throw new IllegalArgumentException("No ohsome snapshot is available for " + month);
        }
        return snapshot;
    }
}

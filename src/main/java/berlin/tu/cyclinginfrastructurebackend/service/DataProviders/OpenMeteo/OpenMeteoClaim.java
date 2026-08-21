package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A bounded, independently addressable set of events claimed for weather enrichment. */
public record OpenMeteoClaim(
        UUID batchId,
        List<OpenMeteoGridYear> gridYears,
        int eventCount
) {
    public OpenMeteoClaim {
        Objects.requireNonNull(batchId, "batchId");
        gridYears = List.copyOf(gridYears);
        if (gridYears.isEmpty()) {
            throw new IllegalArgumentException("A weather claim must contain at least one grid/year");
        }
        if (eventCount < 1) {
            throw new IllegalArgumentException("A weather claim must contain at least one event");
        }
    }
}

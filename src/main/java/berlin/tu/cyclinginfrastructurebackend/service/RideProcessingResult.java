package berlin.tu.cyclinginfrastructurebackend.service;

import java.util.Map;

/** Prepared map-matching output. A zero phase duration means that phase was not reached. */
public record RideProcessingResult(
        boolean success,
        Map<Long, Integer> usageByEdgeId,
        long totalProcessingNanos,
        long graphHopperNanos,
        long timestampCalculationNanos,
        long segmentPreparationNanos
) {

    public RideProcessingResult {
        usageByEdgeId = Map.copyOf(usageByEdgeId);
    }
}

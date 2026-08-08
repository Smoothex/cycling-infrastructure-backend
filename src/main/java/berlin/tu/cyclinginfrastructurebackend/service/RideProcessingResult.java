package berlin.tu.cyclinginfrastructurebackend.service;

/**
 * Internal result of importing and map-matching one ride. A zero phase duration means that the
 * phase was not reached.
 */
public record RideProcessingResult(
        boolean success,
        long totalProcessingNanos,
        long graphHopperNanos,
        long timestampCalculationNanos,
        long segmentUpdateNanos,
        long ridePersistenceNanos
) {
}

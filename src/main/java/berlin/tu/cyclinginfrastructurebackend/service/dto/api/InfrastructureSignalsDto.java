package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

import java.util.List;

/**
 * Preference/avoidance signal balance for one historical OSM attribute.
 * Shares are descriptive associations among local-detour signals.
 */
public record InfrastructureSignalsDto(
        AnalysisDimension dimension,
        long matchingEventCount,
        long knownAttributeEventCount,
        Double coverageShare,
        Double baselineAvoidanceShare,
        List<InfrastructureSignalBucketDto> buckets
) {
    public record InfrastructureSignalBucketDto(
            String value,
            long avoidanceRideCount,
            long preferenceRideCount,
            long totalRideSignals,
            Double avoidanceShare,
            Double percentagePointDifference
    ) {}
}

package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

import java.util.List;
import java.util.Map;

public record RouteComparisonSummaryDto(
        long classifiedRideCount,
        Map<String, Long> routeComparisonTypeCounts,
        double detourThresholdRatio,
        double maximumEquivalentExcessDistanceMeters,
        double minimumOverlapRatio,
        List<DetourImpactDto> detourImpact
) {
}

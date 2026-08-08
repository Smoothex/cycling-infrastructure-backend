package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;

public record DetourImpactDto(
        RouteComparisonType routeComparisonType,
        long eligibleRideCount,
        double lowerQuartilePercent,
        double medianPercent,
        double upperQuartilePercent
) {
}

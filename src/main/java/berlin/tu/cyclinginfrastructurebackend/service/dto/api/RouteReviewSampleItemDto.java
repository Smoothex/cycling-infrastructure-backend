package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;

import java.util.UUID;

public record RouteReviewSampleItemDto(
        UUID rideId,
        int sampleOrder,
        int classSampleRank,
        RouteComparisonType automatedClassification,
        Long startTimestamp,
        String rideIntent,
        String bikeType,
        Double actualDistanceMeters,
        Double shortestPathDistanceMeters,
        Double absoluteExcessDistanceMeters,
        Double relativeDetourRatio,
        Double overlapRatio,
        RouteReviewDto review
) {
}

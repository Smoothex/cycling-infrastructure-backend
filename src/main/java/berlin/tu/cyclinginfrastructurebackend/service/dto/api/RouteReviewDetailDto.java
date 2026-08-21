package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;

import java.util.List;
import java.util.UUID;

public record RouteReviewDetailDto(
        UUID rideId,
        int sampleOrder,
        int classSampleRank,
        RouteComparisonType automatedClassification,
        Long startTimestamp,
        Long endTimestamp,
        Long durationSeconds,
        String rideIntent,
        String bikeType,
        Double actualDistanceMeters,
        Double shortestPathDistanceMeters,
        Double absoluteExcessDistanceMeters,
        Double relativeDetourRatio,
        Double overlapRatio,
        Double medianGpsAccuracyMeters,
        Long gpsPointCount,
        double detourThresholdRatio,
        double maximumEquivalentExcessDistanceMeters,
        double minimumOverlapRatio,
        GeoJsonLineString observedRoute,
        GeoJsonLineString shortestRoute,
        List<RouteSignalDto> signals,
        RouteReviewDto review
) {
    public record GeoJsonLineString(String type, List<List<Double>> coordinates) {
    }

    public record RouteSignalDto(
            UUID eventId,
            Long segmentId,
            String eventType,
            String streetName,
            GeoJsonLineString geometry
    ) {
    }
}

package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

/**
 * A spatially connected component of same-named street segments, ranked by
 * distinct rides carrying a preference or avoidance signal.
 */
public record CorridorRankingDto(
        String streetName,
        long avoidanceRideCount,
        long preferenceRideCount,
        long avoidanceEventCount,
        long preferenceEventCount,
        long segmentCount,
        long scaryIncidentCount,
        Double minLon,
        Double minLat,
        Double maxLon,
        Double maxLat,
        Long topSegmentId
) {}

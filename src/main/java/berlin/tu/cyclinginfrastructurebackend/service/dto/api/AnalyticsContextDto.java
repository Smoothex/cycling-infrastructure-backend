package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

/**
 * Filter-aware evidence context for the analytics tab.
 * Ride counts describe rides with at least one matching route-choice signal,
 * not unique cyclists.
 */
public record AnalyticsContextDto(
        long matchingRideCount,
        long matchingEventCount,
        long avoidanceEventCount,
        long preferenceEventCount,
        Long earliestEventTimestamp,
        Long latestEventTimestamp
) {}

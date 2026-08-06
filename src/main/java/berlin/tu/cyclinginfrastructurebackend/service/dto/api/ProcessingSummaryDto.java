package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

import java.util.Map;

public record ProcessingSummaryDto(
        long totalRides,
        Map<String, Long> rideStatusCounts,
        Map<String, Long> routeComparisonTypeCounts,
        long totalSegments,
        long observedSegments,
        long totalSegmentEvents,
        Long earliestEventTimestamp,
        Long latestEventTimestamp,
        Map<String, Long> segmentEventTypeCounts,
        long weatherEnrichedEvents,
        long ohsomeEnrichedEvents,
        long berlinOpenDataEnrichedEvents,
        long trafficEnrichedEvents,
        long trafficMeasuredEvents
) {
}

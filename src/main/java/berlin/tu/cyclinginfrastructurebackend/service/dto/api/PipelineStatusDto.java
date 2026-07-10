package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

import java.util.Map;

public record PipelineStatusDto(
        Map<String, Long> rideStatusCounts,
        long totalSegmentEvents,
        Map<String, Long> weatherStatusCounts,
        Map<String, Long> berlinOpenDataStatusCounts,
        Map<String, Long> ohsomeStatusCounts,
        Map<String, Long> trafficStatusCounts
) {
}

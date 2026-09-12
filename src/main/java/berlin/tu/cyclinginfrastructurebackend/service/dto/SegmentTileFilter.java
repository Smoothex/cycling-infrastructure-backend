package berlin.tu.cyclinginfrastructurebackend.service.dto;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.RideIntent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEnrichmentFilter;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficCondition;

import java.util.Set;

/** The same event must satisfy every selected condition. Set equality canonicalizes filter order. */
public record SegmentTileFilter(long from, long to, RideIntent rideIntent,
                                TrafficCondition trafficCondition, Set<SegmentEnrichmentFilter> enrichments) {
    public SegmentTileFilter {
        if (from > to) {
            throw new IllegalArgumentException("from must not exceed to");
        }
        enrichments = enrichments == null ? Set.of() : Set.copyOf(enrichments);
    }
}

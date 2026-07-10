package berlin.tu.cyclinginfrastructurebackend.domain.enums;

/**
 * Enrichment-based filters accepted by the segments API. A segment (or event)
 * matches a filter when the corresponding enrichment actually attached data:
 * TRAFFIC_MEASURED means a detector measurement was matched
 * ({@link TrafficEnrichmentStatus#ENRICHED}), not merely that the traffic
 * pipeline ran over the event.
 */
public enum SegmentEnrichmentFilter {
    TRAFFIC_ENRICHED,
    WEATHER_ENRICHED,
    OHSOME_ENRICHED,
    TRAFFIC_MEASURED
}

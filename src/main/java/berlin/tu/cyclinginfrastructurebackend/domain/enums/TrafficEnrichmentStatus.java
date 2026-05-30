package berlin.tu.cyclinginfrastructurebackend.domain.enums;

public enum TrafficEnrichmentStatus {
    ENRICHED,
    NO_DETECTOR_MATCH,
    NO_SOURCE_FILE,
    NO_MEASUREMENT,
    LOW_QUALITY,
    ERROR
}

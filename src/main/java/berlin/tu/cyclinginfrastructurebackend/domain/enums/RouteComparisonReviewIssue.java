package berlin.tu.cyclinginfrastructurebackend.domain.enums;

/** Predefined data-quality observations available during manual review. */
public enum RouteComparisonReviewIssue {
    MAP_MATCHING_ERROR,
    IMPLAUSIBLE_REFERENCE_ROUTE,
    LIKELY_INTERMEDIATE_STOP,
    INCORRECT_DIVERGENT_SEGMENTS,
    POOR_GPS_QUALITY,
    OTHER
}

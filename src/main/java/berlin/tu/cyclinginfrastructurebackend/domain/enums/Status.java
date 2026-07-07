package berlin.tu.cyclinginfrastructurebackend.domain.enums;

public enum Status {
    PENDING,            // Map-matched, waiting for detour analysis
    ANALYZING,          // Claimed by the detour-analysis worker
    PROCESSED,          // Analyzed successfully
    ALTERNATIVE_ROUTE,
    SKIPPED,            // too short, invalid points, or routing failed
    ERROR
}

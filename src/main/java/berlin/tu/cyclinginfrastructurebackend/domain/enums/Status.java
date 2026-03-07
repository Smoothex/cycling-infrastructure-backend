package berlin.tu.cyclinginfrastructurebackend.domain.enums;

public enum Status {
    PENDING,            // Map-matched, waiting for detour analysis
    PROCESSED,          // Analyzed successfully
    ALTERNATIVE_ROUTE,
    SKIPPED,            // too short, invalid points, or routing failed
    ERROR
}
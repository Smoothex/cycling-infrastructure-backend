package berlin.tu.cyclinginfrastructurebackend.domain.enums;

public enum Status {
    PENDING,            // Retained for API compatibility; clean imports do not persist it
    ANALYZING,          // Retained for API compatibility; clean imports do not persist it
    PROCESSED,          // Analyzed successfully
    SKIPPED,            // too short, invalid points, or routing failed
    ERROR
}

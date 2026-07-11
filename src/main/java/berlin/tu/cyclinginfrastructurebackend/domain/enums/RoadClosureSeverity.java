package berlin.tu.cyclinginfrastructurebackend.domain.enums;

/**
 * Closure severity as reported by the VIZ Berlin Baustellen/Sperrungen feed.
 */
public enum RoadClosureSeverity {
    NO_CLOSURE,
    FULL_CLOSURE,
    DIRECTIONAL_CLOSURE,
    UNKNOWN;

    public static RoadClosureSeverity fromLabel(String label) {
        if (label == null) {
            return UNKNOWN;
        }
        return switch (label) {
            case "keine Sperrung" -> NO_CLOSURE;
            case "Vollsperrung" -> FULL_CLOSURE;
            case "Fahrtrichtungssperrung" -> DIRECTIONAL_CLOSURE;
            default -> UNKNOWN;
        };
    }
}

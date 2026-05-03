package berlin.tu.cyclinginfrastructurebackend.domain.enums;

public enum CyclewayLocation {
    NONE,      // No cycleway present
    LEFT,      // Only left side
    RIGHT,     // Only right side
    BOTH,      // Both sides
    UNKNOWN    // Generic cycleway tag without direction
}

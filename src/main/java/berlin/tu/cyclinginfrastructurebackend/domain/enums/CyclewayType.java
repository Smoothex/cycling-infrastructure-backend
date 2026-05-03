package berlin.tu.cyclinginfrastructurebackend.domain.enums;

// https://wiki.openstreetmap.org/wiki/Key:cycleway
public enum CyclewayType {
    TRACK,           // Physically separated bike lane
    LANE,            // Painted bike lane on road
    SHARED_LANE,     // Shared with cars
    SHARE_BUSWAY,    // Shared with bus lane
    NO,              // Explicitly no cycleway
    SEPARATE,        // Separate cycleway (not on road)
    CROSSING,        // Cycleway crossing
    UNKNOWN;         // Other

    public static CyclewayType fromOsmValue(String value) {
        if (value == null) return null;

        return switch (value.toLowerCase()) {
            case "track" -> TRACK;
            case "lane" -> LANE;
            case "shared_lane" -> SHARED_LANE;
            case "share_busway" -> SHARE_BUSWAY;
            case "no" -> NO;
            case "separate" -> SEPARATE;
            case "crossing" -> CROSSING;

            // Deprecated tags
            case "opposite" -> NO;
            case "opposite_lane" -> LANE;
            case "opposite_track" -> TRACK;

            default -> UNKNOWN;
        };
    }
}

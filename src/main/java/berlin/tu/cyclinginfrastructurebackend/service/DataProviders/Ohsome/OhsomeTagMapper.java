package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayLocation;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Pure conversion from historical OSM tags to the existing enrichment attributes. */
@Component
public class OhsomeTagMapper {

    private static final Logger log = LoggerFactory.getLogger(OhsomeTagMapper.class);

    public OhsomeInfrastructureAttributes map(Map<String, String> sourceTags) {
        Map<String, String> tags = new HashMap<>(sourceTags == null ? Map.of() : sourceTags);
        normalizeDeprecatedCyclewayTag(tags);

        CyclewaySelection cycleway = selectCycleway(tags);
        CyclewayType type = cycleway.value() == null
                ? null
                : CyclewayType.fromOsmValue(cycleway.value());

        String cyclewaySurface = null;
        Double cyclewayWidth = null;
        if (type != null && type != CyclewayType.NO) {
            cyclewaySurface = cyclewaySurface(tags, cycleway.location());
            cyclewayWidth = cyclewayWidth(tags, cycleway.location());
        }

        return new OhsomeInfrastructureAttributes(
                tags.get("surface"),
                tags.get("smoothness"),
                tags.get("lit"),
                tags.get("highway"),
                type,
                cycleway.location(),
                cyclewaySurface,
                cyclewayWidth,
                bicycleOneway(tags.get("oneway:bicycle"))
        );
    }

    private void normalizeDeprecatedCyclewayTag(Map<String, String> tags) {
        switch (tags.getOrDefault("cycleway", "")) {
            case "opposite" -> {
                tags.putIfAbsent("oneway:bicycle", "no");
                tags.put("cycleway", "no");
            }
            case "opposite_lane" -> {
                tags.putIfAbsent("oneway:bicycle", "no");
                tags.put("cycleway", "lane");
            }
            case "opposite_track" -> {
                tags.putIfAbsent("oneway:bicycle", "no");
                tags.put("cycleway", "track");
            }
            default -> {
                // No normalization required.
            }
        }
    }

    private CyclewaySelection selectCycleway(Map<String, String> tags) {
        if (tags.containsKey("cycleway:both")) {
            return new CyclewaySelection(tags.get("cycleway:both"), CyclewayLocation.BOTH);
        }
        if (tags.containsKey("cycleway:right")) {
            return new CyclewaySelection(tags.get("cycleway:right"), CyclewayLocation.RIGHT);
        }
        if (tags.containsKey("cycleway:left")) {
            return new CyclewaySelection(tags.get("cycleway:left"), CyclewayLocation.LEFT);
        }
        if (tags.containsKey("cycleway")) {
            return new CyclewaySelection(tags.get("cycleway"), CyclewayLocation.UNKNOWN);
        }
        return new CyclewaySelection(null, CyclewayLocation.NONE);
    }

    private String cyclewaySurface(Map<String, String> tags, CyclewayLocation location) {
        String surface = switch (location) {
            case BOTH -> tags.get("cycleway:both:surface");
            case RIGHT -> tags.get("cycleway:right:surface");
            case LEFT -> tags.get("cycleway:left:surface");
            case UNKNOWN -> tags.get("cycleway:surface");
            case NONE -> null;
        };
        return surface == null ? tags.get("surface") : surface;
    }

    private Double cyclewayWidth(Map<String, String> tags, CyclewayLocation location) {
        String value = switch (location) {
            case BOTH -> tags.get("cycleway:both:width");
            case RIGHT -> tags.get("cycleway:right:width");
            case LEFT -> tags.get("cycleway:left:width");
            case UNKNOWN -> tags.get("cycleway:width");
            case NONE -> null;
        };
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            log.warn("Invalid cycleway width value: {}", value);
            return null;
        }
    }

    private Boolean bicycleOneway(String value) {
        if ("yes".equals(value)) {
            return true;
        }
        if ("no".equals(value) || "-1".equals(value)) {
            return false;
        }
        return null;
    }

    private record CyclewaySelection(String value, CyclewayLocation location) {
    }
}

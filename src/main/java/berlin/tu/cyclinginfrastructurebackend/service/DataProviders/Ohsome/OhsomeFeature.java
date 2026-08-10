package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import org.locationtech.jts.geom.LineString;

import java.util.Map;
import java.util.Objects;

/** One historical OSM way read from an ohsome v2 GeoParquet snapshot. */
public record OhsomeFeature(long osmId, LineString geometry, Map<String, String> tags) {

    public OhsomeFeature {
        Objects.requireNonNull(geometry, "geometry");
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    public String name() {
        return tags.get("name");
    }
}

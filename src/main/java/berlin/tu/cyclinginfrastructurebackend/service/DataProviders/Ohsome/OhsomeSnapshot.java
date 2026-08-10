package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import org.locationtech.jts.geom.LineString;

import java.util.Collection;
import java.util.Objects;

/** One in-memory monthly snapshot and its single deterministic spatial index. */
public final class OhsomeSnapshot {

    private final long featureCount;
    private final OhsomeFeatureMatcher matcher;

    public OhsomeSnapshot(Collection<OhsomeFeature> features) {
        Objects.requireNonNull(features, "features");
        featureCount = features.size();
        matcher = new OhsomeFeatureMatcher(features);
    }

    public long featureCount() {
        return featureCount;
    }

    public OhsomeFeatureMatch match(LineString segment, String streetName) {
        return matcher.match(segment, streetName);
    }
}

package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import java.util.Objects;
import java.util.Optional;

/** Deterministic outcome of matching one GraphHopper segment to a historical OSM way. */
public record OhsomeFeatureMatch(Outcome outcome, OhsomeFeature matchedFeature) {

    public OhsomeFeatureMatch {
        Objects.requireNonNull(outcome, "outcome");
        if ((outcome == Outcome.MATCHED) != (matchedFeature != null)) {
            throw new IllegalArgumentException("Only MATCHED outcomes may carry a feature");
        }
    }

    public static OhsomeFeatureMatch matched(OhsomeFeature feature) {
        return new OhsomeFeatureMatch(Outcome.MATCHED, Objects.requireNonNull(feature, "feature"));
    }

    public static OhsomeFeatureMatch noMatch() {
        return new OhsomeFeatureMatch(Outcome.NO_MATCH, null);
    }

    public static OhsomeFeatureMatch ambiguous() {
        return new OhsomeFeatureMatch(Outcome.AMBIGUOUS, null);
    }

    public Optional<OhsomeFeature> feature() {
        return Optional.ofNullable(matchedFeature);
    }

    public enum Outcome {
        MATCHED,
        NO_MATCH,
        AMBIGUOUS
    }
}

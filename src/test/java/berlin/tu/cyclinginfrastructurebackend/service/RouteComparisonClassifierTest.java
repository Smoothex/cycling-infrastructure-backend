package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteComparisonClassifierTest {

    private final RouteComparisonClassifier classifier = new RouteComparisonClassifier(0.10, 0.30);

    @Test
    void exactDetourThresholdIsEquivalent() {
        assertThat(classifier.classify(1_100.0, 1_000.0, 0.20))
                .isEqualTo(RouteComparisonType.EQUIVALENT_ROUTE);
    }

    @Test
    void exactOverlapThresholdIsLocalDetour() {
        assertThat(classifier.classify(1_100.01, 1_000.0, 0.30))
                .isEqualTo(RouteComparisonType.LOCAL_DETOUR);
    }

    @Test
    void overlapBelowThresholdIsCorridorAlternative() {
        assertThat(classifier.classify(1_100.01, 1_000.0, 0.2999))
                .isEqualTo(RouteComparisonType.CORRIDOR_ALTERNATIVE);
    }

    @Test
    void invalidShortestDistanceIsRejected() {
        assertThatThrownBy(() -> classifier.classify(1_000.0, 0.0, 0.50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Shortest-path distance");
    }
}

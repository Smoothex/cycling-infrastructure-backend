package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteComparisonClassifierTest {

    private final RouteComparisonClassifier classifier = new RouteComparisonClassifier(0.10, 500.0, 0.30);

    @Test
    void exactRelativeThresholdIsEquivalent() {
        assertThat(classifier.classify(1_100.0, 1_000.0, 0.20))
                .isEqualTo(RouteComparisonType.EQUIVALENT_ROUTE);
    }

    @Test
    void relativeThresholdExceededBelowAbsoluteCapIsLocalDetour() {
        assertThat(classifier.classify(1_100.01, 1_000.0, 0.30))
                .isEqualTo(RouteComparisonType.LOCAL_DETOUR);
    }

    @Test
    void exactAbsoluteCapIsEquivalent() {
        assertThat(classifier.classify(10_500.0, 10_000.0, 0.20))
                .isEqualTo(RouteComparisonType.EQUIVALENT_ROUTE);
    }

    @Test
    void absoluteCapExceededBelowRelativeThresholdIsLocalDetourAtExactOverlapThreshold() {
        assertThat(classifier.classify(10_500.01, 10_000.0, 0.30))
                .isEqualTo(RouteComparisonType.LOCAL_DETOUR);
    }

    @Test
    void absoluteCapExceededBelowRelativeThresholdIsCorridorAlternativeBelowOverlapThreshold() {
        assertThat(classifier.classify(10_500.01, 10_000.0, 0.2999))
                .isEqualTo(RouteComparisonType.CORRIDOR_ALTERNATIVE);
    }

    @Test
    void observedDistanceBelowShortestDistanceIsEquivalent() {
        assertThat(classifier.classify(900.0, 1_000.0, 0.0))
                .isEqualTo(RouteComparisonType.EQUIVALENT_ROUTE);
    }

    @Test
    void zeroExcessDistanceIsEquivalent() {
        assertThat(classifier.classify(1_000.0, 1_000.0, 0.0))
                .isEqualTo(RouteComparisonType.EQUIVALENT_ROUTE);
    }

    @Test
    void screenshotRouteIsLocalDetour() {
        assertThat(classifier.classify(10_712.0, 9_807.0, 0.74))
                .isEqualTo(RouteComparisonType.LOCAL_DETOUR);
    }

    @Test
    void invalidShortestDistanceIsRejected() {
        assertThatThrownBy(() -> classifier.classify(1_000.0, 0.0, 0.50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Shortest-path distance");
    }

    @Test
    void nonPositiveAbsoluteCapIsRejected() {
        assertThatThrownBy(() -> new RouteComparisonClassifier(0.10, 0.0, 0.30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite and positive");
    }

    @Test
    void nonFiniteAbsoluteCapIsRejected() {
        assertThatThrownBy(() -> new RouteComparisonClassifier(0.10, Double.NaN, 0.30))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finite and positive");
    }
}

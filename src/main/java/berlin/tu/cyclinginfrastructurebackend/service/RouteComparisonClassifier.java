package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RouteComparisonClassifier {

    private final double detourThreshold;
    private final double minimumRouteOverlapRatio;

    public RouteComparisonClassifier(
            @Value("${analysis.detour.threshold}") double detourThreshold,
            @Value("${analysis.route-overlap.minimum-ratio}") double minimumRouteOverlapRatio) {
        this.detourThreshold = detourThreshold;
        this.minimumRouteOverlapRatio = minimumRouteOverlapRatio;
    }

    public RouteComparisonType classify(double actualDistance,
                                        double shortestPathDistance,
                                        double overlapRatio) {
        validateMetrics(actualDistance, shortestPathDistance, overlapRatio);

        if (actualDistance <= shortestPathDistance * (1.0 + detourThreshold)) {
            return RouteComparisonType.EQUIVALENT_ROUTE;
        }

        if (overlapRatio < minimumRouteOverlapRatio) {
            return RouteComparisonType.CORRIDOR_ALTERNATIVE;
        }

        return RouteComparisonType.LOCAL_DETOUR;
    }

    private void validateMetrics(double actualDistance,
                                 double shortestPathDistance,
                                 double overlapRatio) {
        if (!Double.isFinite(actualDistance) || actualDistance < 0.0) {
            throw new IllegalArgumentException("Actual route distance must be finite and non-negative");
        }
        if (!Double.isFinite(shortestPathDistance) || shortestPathDistance <= 0.0) {
            throw new IllegalArgumentException("Shortest-path distance must be finite and positive");
        }
        if (!Double.isFinite(overlapRatio) || overlapRatio < 0.0 || overlapRatio > 1.0) {
            throw new IllegalArgumentException("Route overlap ratio must be between zero and one");
        }
    }
}

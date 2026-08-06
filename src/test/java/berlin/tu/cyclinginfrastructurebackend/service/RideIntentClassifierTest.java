package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RideIntent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RideIntentClassifierTest {

    private final RideIntentClassifier classifier = new RideIntentClassifier();

    @Test
    void corridorAlternativePreservesPreviousIntentPenalty() {
        Ride ride = processedRide(RouteComparisonType.CORRIDOR_ALTERNATIVE);

        classifier.classify(ride);

        assertThat(ride.getRideIntent()).isEqualTo(RideIntent.LEISURE);
    }

    @Test
    void localDetourDoesNotReceiveCorridorPenalty() {
        Ride ride = processedRide(RouteComparisonType.LOCAL_DETOUR);

        classifier.classify(ride);

        assertThat(ride.getRideIntent()).isEqualTo(RideIntent.UNKNOWN);
    }

    private Ride processedRide(RouteComparisonType type) {
        Ride ride = new Ride();
        ride.setStatus(Status.PROCESSED);
        ride.setRouteComparisonType(type);
        ride.setIsDetour(type != RouteComparisonType.EQUIVALENT_ROUTE);
        return ride;
    }
}

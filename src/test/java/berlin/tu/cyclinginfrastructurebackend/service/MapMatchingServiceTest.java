package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.RidePoint;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MapMatchingServiceTest {

    private final GraphHopperService graphHopperService = mock(GraphHopperService.class);
    private final StreetSegmentService streetSegmentService = mock(StreetSegmentService.class);
    private final RideRepository rideRepository = mock(RideRepository.class);
    private final MapMatchingService service = new MapMatchingService(
            graphHopperService, streetSegmentService, rideRepository, 500.0);
    private final GeometryFactory geometryFactory = new GeometryFactory();

    @Test
    void skipsRideBelowMinimumOriginDestinationDistanceBeforeMapMatchingAndUsageCounting() {
        Ride ride = new Ride();
        ride.getRidePoints().add(point(52.5200, 13.4050, 1_000L));
        ride.getRidePoints().add(point(52.5205, 13.4055, 2_000L));

        boolean processed = service.processRide(ride);

        assertThat(processed).isTrue();
        assertThat(ride.getStatus()).isEqualTo(Status.SKIPPED);
        verify(rideRepository).save(same(ride));
        verifyNoInteractions(graphHopperService, streetSegmentService);
    }

    private RidePoint point(double lat, double lon, long timestamp) {
        RidePoint point = new RidePoint();
        point.setLocation(geometryFactory.createPoint(new Coordinate(lon, lat)));
        point.setTimestamp(timestamp);
        return point;
    }
}

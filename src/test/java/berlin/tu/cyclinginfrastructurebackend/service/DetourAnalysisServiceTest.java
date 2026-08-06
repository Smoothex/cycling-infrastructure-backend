package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.RidePoint;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.PointList;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DetourAnalysisServiceTest {

    private final GraphHopperService graphHopperService = mock(GraphHopperService.class);
    private final RideRepository rideRepository = mock(RideRepository.class);
    private final StreetSegmentService streetSegmentService = mock(StreetSegmentService.class);
    private final StreetSegmentRepository streetSegmentRepository = mock(StreetSegmentRepository.class);
    private final RideIntentClassifier rideIntentClassifier = mock(RideIntentClassifier.class);
    private final DetourAnalysisService service = service();

    @Test
    void nonDetourStoresSpatialLengthOverlapWithoutGeneratingEvents() {
        Ride ride = ride(1_050.0);
        ResponsePath shortestPath = shortestPath(1_000.0);
        when(graphHopperService.getShortestPath(52.5, 13.4, 52.5, 13.41))
                .thenReturn(shortestPath);
        when(rideRepository.calculateSpatialLengthOverlap(anyString(), anyString(), eq(20.0)))
                .thenReturn(0.75);

        Status result = service.analyzeLoadedRide(ride);

        assertThat(result).isEqualTo(Status.PROCESSED);
        assertThat(ride.getIsDetour()).isFalse();
        assertThat(ride.getOverlapRatio()).isEqualTo(0.75);
        assertThat(ride.getRouteComparisonType()).isEqualTo(RouteComparisonType.EQUIVALENT_ROUTE);
        verify(rideIntentClassifier).classify(ride);
        verifyNoInteractions(streetSegmentService, streetSegmentRepository);
    }

    @Test
    void lowLengthOverlapClassifiesProcessedCorridorAlternativeWithoutEvents() {
        Ride ride = ride(1_200.0);
        ResponsePath shortestPath = shortestPath(1_000.0);
        when(graphHopperService.getShortestPath(52.5, 13.4, 52.5, 13.41))
                .thenReturn(shortestPath);
        when(rideRepository.calculateSpatialLengthOverlap(anyString(), anyString(), eq(20.0)))
                .thenReturn(0.2999);

        Status result = service.analyzeLoadedRide(ride);

        assertThat(result).isEqualTo(Status.PROCESSED);
        assertThat(ride.getStatus()).isEqualTo(Status.PROCESSED);
        assertThat(ride.getIsDetour()).isTrue();
        assertThat(ride.getOverlapRatio()).isEqualTo(0.2999);
        assertThat(ride.getRouteComparisonType()).isEqualTo(RouteComparisonType.CORRIDOR_ALTERNATIVE);
        verify(rideIntentClassifier).classify(ride);
        verifyNoInteractions(streetSegmentService, streetSegmentRepository);
    }

    @Test
    void localDetourGeneratesSegmentEvents() {
        Ride ride = ride(1_200.0);
        ResponsePath shortestPath = shortestPath(1_000.0);
        when(graphHopperService.getShortestPath(52.5, 13.4, 52.5, 13.41))
                .thenReturn(shortestPath);
        when(rideRepository.calculateSpatialLengthOverlap(anyString(), anyString(), eq(20.0)))
                .thenReturn(0.30);
        when(streetSegmentRepository.findEdgeIdsWithinDistance(
                org.mockito.ArgumentMatchers.anyList(), anyString(), eq(20.0)))
                .thenReturn(List.of());
        when(graphHopperService.getHopper()).thenReturn(mock(com.graphhopper.GraphHopper.class));

        Status result = service.analyzeLoadedRide(ride);

        assertThat(result).isEqualTo(Status.PROCESSED);
        assertThat(ride.getRouteComparisonType()).isEqualTo(RouteComparisonType.LOCAL_DETOUR);
        verify(streetSegmentService).registerSegmentEvents(
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyMap(),
                eq(ride),
                eq(graphHopperService));
    }

    private DetourAnalysisService service() {
        DetourAnalysisService result = new DetourAnalysisService(
                graphHopperService,
                rideRepository,
                streetSegmentService,
                streetSegmentRepository,
                rideIntentClassifier,
                new RouteComparisonClassifier(0.10, 0.30),
                mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(result, "proximityMeters", 20.0);
        return result;
    }

    private Ride ride(double actualDistance) {
        GeometryFactory geometryFactory = new GeometryFactory();
        Ride ride = new Ride();
        ride.setActualDistance(actualDistance);
        ride.setTrajectory(geometryFactory.createLineString(new Coordinate[]{
                new Coordinate(13.4, 52.5),
                new Coordinate(13.41, 52.5)
        }));
        ride.setTraversedEdgeIds(List.of(1));

        RidePoint start = new RidePoint();
        start.setLocation(geometryFactory.createPoint(new Coordinate(13.4, 52.5)));
        start.setTimestamp(1_000L);
        RidePoint end = new RidePoint();
        end.setLocation(geometryFactory.createPoint(new Coordinate(13.41, 52.5)));
        end.setTimestamp(2_000L);
        ride.setRidePoints(List.of(start, end));
        return ride;
    }

    private ResponsePath shortestPath(double distance) {
        ResponsePath path = mock(ResponsePath.class);
        PointList points = new PointList();
        points.add(52.5, 13.4);
        points.add(52.5, 13.41);
        when(path.getDistance()).thenReturn(distance);
        when(path.getPoints()).thenReturn(points);
        when(path.getPathDetails()).thenReturn(Map.of());
        return path;
    }
}

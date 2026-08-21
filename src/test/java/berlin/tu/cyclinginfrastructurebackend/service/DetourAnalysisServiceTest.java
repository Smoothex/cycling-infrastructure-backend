package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import com.graphhopper.util.details.PathDetail;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.test.util.ReflectionTestUtils;

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
    private final GeometryFactory geometryFactory = new GeometryFactory();

    @Test
    void equivalentRidePreparesNoEventsOrWrites() {
        Ride ride = ride(1_050.0);
        ResponsePath shortestPath = shortestPath(1_000.0);
        when(graphHopperService.getShortestPath(52.5, 13.4, 52.5, 13.41))
                .thenReturn(shortestPath);
        when(rideRepository.calculateSpatialLengthOverlap(anyString(), anyString(), eq(20.0)))
                .thenReturn(0.75);

        DetourAnalysisResult result = service.analyzeRide(ride, trace());

        assertThat(result).isEqualTo(DetourAnalysisResult.empty());
        assertThat(ride.getStatus()).isEqualTo(Status.PROCESSED);
        assertThat(ride.getIsDetour()).isFalse();
        assertThat(ride.getRouteComparisonType()).isEqualTo(RouteComparisonType.EQUIVALENT_ROUTE);
        verify(rideIntentClassifier).classify(ride);
        verifyNoInteractions(streetSegmentService, streetSegmentRepository);
    }

    @Test
    void absoluteCapTriggeredCorridorAlternativePreparesNoEvents() {
        Ride ride = ride(10_500.01);
        ResponsePath shortestPath = shortestPath(10_000.0);
        when(graphHopperService.getShortestPath(52.5, 13.4, 52.5, 13.41))
                .thenReturn(shortestPath);
        when(rideRepository.calculateSpatialLengthOverlap(anyString(), anyString(), eq(20.0)))
                .thenReturn(0.2999);

        DetourAnalysisResult result = service.analyzeRide(ride, trace());

        assertThat(result).isEqualTo(DetourAnalysisResult.empty());
        assertThat(ride.getRouteComparisonType()).isEqualTo(RouteComparisonType.CORRIDOR_ALTERNATIVE);
        assertThat(ride.getIsDetour()).isTrue();
        assertThat(ride.getStatus()).isEqualTo(Status.PROCESSED);
        verifyNoInteractions(streetSegmentService, streetSegmentRepository);
    }

    @Test
    void absoluteCapTriggeredLocalDetourReturnsPreparedChosenEventsWithoutPersistingThem() {
        Ride ride = ride(10_500.01);
        ResponsePath shortestPath = shortestPath(10_000.0);
        when(graphHopperService.getShortestPath(52.5, 13.4, 52.5, 13.41))
                .thenReturn(shortestPath);
        when(rideRepository.calculateSpatialLengthOverlap(anyString(), anyString(), eq(20.0)))
                .thenReturn(0.30);
        when(streetSegmentRepository.findEdgeIdsWithinDistance(
                org.mockito.ArgumentMatchers.anyList(), anyString(), eq(20.0)))
                .thenReturn(List.of());

        DetourAnalysisResult result = service.analyzeRide(ride, trace());

        assertThat(ride.getRouteComparisonType()).isEqualTo(RouteComparisonType.LOCAL_DETOUR);
        assertThat(ride.getIsDetour()).isTrue();
        assertThat(result.chosenEdgeBearings()).containsEntry(1, 90.0);
        assertThat(result.chosenEdgeTimestamps()).containsEntry(1, 2_000L);
        verify(streetSegmentService).ensureSegmentsExist(
                org.mockito.ArgumentMatchers.argThat(ids -> ids.size() == 1 && ids.contains(1)),
                eq(graphHopperService));
    }

    @Test
    void shortestPathFailureSkipsRideAndPreparesNoEvents() {
        Ride ride = ride(1_200.0);
        when(graphHopperService.getShortestPath(52.5, 13.4, 52.5, 13.41)).thenReturn(null);

        DetourAnalysisResult result = service.analyzeRide(ride, trace());

        assertThat(result).isEqualTo(DetourAnalysisResult.empty());
        assertThat(ride.getStatus()).isEqualTo(Status.SKIPPED);
        verifyNoInteractions(streetSegmentService, streetSegmentRepository);
    }

    @Test
    void avoidedEdgeUsesTimestampOfNearestCanonicalTracePoint() {
        Ride ride = ride(1_200.0);
        PathDetail edgeDetail = mock(PathDetail.class);
        when(edgeDetail.getValue()).thenReturn(2);
        when(edgeDetail.getFirst()).thenReturn(0);
        when(edgeDetail.getLast()).thenReturn(1);
        ResponsePath shortestPath = shortestPath(1_000.0);
        when(shortestPath.getPathDetails()).thenReturn(Map.of("edge_id", List.of(edgeDetail)));
        when(graphHopperService.getShortestPath(52.5, 13.4, 52.5, 13.41))
                .thenReturn(shortestPath);
        when(rideRepository.calculateSpatialLengthOverlap(anyString(), anyString(), eq(20.0)))
                .thenReturn(0.30);
        when(streetSegmentRepository.findEdgeIdsWithinDistance(
                org.mockito.ArgumentMatchers.anyList(), anyString(), eq(20.0)))
                .thenReturn(List.of());

        GraphHopper hopper = mock(GraphHopper.class);
        BaseGraph baseGraph = mock(BaseGraph.class);
        EdgeIteratorState avoidedEdge = mock(EdgeIteratorState.class);
        PointList avoidedGeometry = new PointList();
        avoidedGeometry.add(52.5, 13.409);
        avoidedGeometry.add(52.5, 13.410);
        when(graphHopperService.getHopper()).thenReturn(hopper);
        when(hopper.getBaseGraph()).thenReturn(baseGraph);
        when(baseGraph.getEdgeIteratorState(2, Integer.MIN_VALUE)).thenReturn(avoidedEdge);
        when(avoidedEdge.fetchWayGeometry(FetchMode.ALL)).thenReturn(avoidedGeometry);

        DetourAnalysisResult result = service.analyzeRide(ride, trace());

        assertThat(result.avoidedEdgeTimestamps()).containsEntry(2, 2_000L);
    }

    private DetourAnalysisService service() {
        DetourAnalysisService result = new DetourAnalysisService(
                graphHopperService,
                rideRepository,
                streetSegmentService,
                streetSegmentRepository,
                rideIntentClassifier,
                new RouteComparisonClassifier(0.10, 500.0, 0.30));
        ReflectionTestUtils.setField(result, "proximityMeters", 20.0);
        return result;
    }

    private Ride ride(double actualDistance) {
        Ride ride = new Ride();
        ride.setStartTime(1_000L);
        ride.setEndTime(2_000L);
        ride.setActualDistance(actualDistance);
        ride.setTrajectory(geometryFactory.createLineString(new Coordinate[]{
                new Coordinate(13.4, 52.5),
                new Coordinate(13.41, 52.5)
        }));
        ride.setTraversedEdgeIds(List.of(1));
        ride.setTraversedEdgeBearings(Map.of(1, 90.0));
        ride.setTraversedEdgeTimestamps(Map.of(1, 2_000L));
        return ride;
    }

    private List<RideTracePoint> trace() {
        return List.of(
                new RideTracePoint(geometryFactory.createPoint(new Coordinate(13.4, 52.5)), 1_000L),
                new RideTracePoint(geometryFactory.createPoint(new Coordinate(13.41, 52.5)), 2_000L));
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

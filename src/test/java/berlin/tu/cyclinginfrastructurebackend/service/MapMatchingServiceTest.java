package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import com.graphhopper.matching.EdgeMatch;
import com.graphhopper.matching.MatchResult;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MapMatchingServiceTest {

    private final GraphHopperService graphHopperService = mock(GraphHopperService.class);
    private final StreetSegmentService streetSegmentService = mock(StreetSegmentService.class);
    private final MapMatchingService service = new MapMatchingService(
            graphHopperService, streetSegmentService, 500.0);
    private final GeometryFactory geometryFactory = new GeometryFactory();

    @Test
    void canonicalTraceFiltersInvalidSamplesAndSortsChronologically() {
        List<RideTracePoint> canonical = service.canonicalizeTrace(List.of(
                point(52.52, 13.41, 3_000L),
                new RideTracePoint(null, 2_000L),
                point(0.0, 13.40, 1_000L),
                point(52.51, 13.40, 1_000L)));

        assertThat(canonical).extracting(RideTracePoint::timestamp).containsExactly(1_000L, 3_000L);
    }

    @Test
    void skipsRideBelowMinimumDistanceWithoutUsageOrPersistence() {
        Ride ride = new Ride();
        List<RideTracePoint> trace = List.of(
                point(52.5200, 13.4050, 1_000L),
                point(52.5205, 13.4055, 2_000L));

        RideProcessingResult result = service.processRide(ride, trace);

        assertThat(result.success()).isTrue();
        assertThat(result.usageByEdgeId()).isEmpty();
        assertThat(result.graphHopperNanos()).isZero();
        assertThat(result.timestampCalculationNanos()).isZero();
        assertThat(result.segmentPreparationNanos()).isZero();
        assertThat(ride.getStatus()).isEqualTo(Status.SKIPPED);
        verifyNoInteractions(graphHopperService, streetSegmentService);
    }

    @Test
    void preparesDuplicateEdgeOccurrenceCountsWithoutUpdatingCounters() {
        Ride ride = new Ride();
        List<RideTracePoint> trace = List.of(
                point(52.50, 13.30, 1_000L),
                point(52.50, 13.40, 2_000L));
        EdgeIteratorState edge42First = edge(42, 13.30, 13.34);
        EdgeIteratorState edge12 = edge(12, 13.34, 13.37);
        EdgeIteratorState edge42Second = edge(42, 13.37, 13.40);
        List<EdgeMatch> edgeMatches = List.of(
                edgeMatch(edge42First), edgeMatch(edge12), edgeMatch(edge42Second));
        MatchResult matchResult = mock(MatchResult.class);
        when(matchResult.getMatchLength()).thenReturn(1_000.0);
        when(matchResult.getEdgeMatches()).thenReturn(edgeMatches);
        when(graphHopperService.match(org.mockito.ArgumentMatchers.anyList())).thenReturn(matchResult);

        RideProcessingResult result = service.processRide(ride, trace);

        assertThat(result.success()).isTrue();
        assertThat(result.usageByEdgeId()).isEqualTo(Map.of(12L, 1, 42L, 2));
        assertThat(ride.getTraversedEdgeIds()).containsExactly(42, 12, 42);
        verify(streetSegmentService).ensureSegmentsExist(List.of(12, 42), graphHopperService);
    }

    private EdgeMatch edgeMatch(EdgeIteratorState edge) {
        EdgeMatch match = mock(EdgeMatch.class);
        when(match.getEdgeState()).thenReturn(edge);
        return match;
    }

    private EdgeIteratorState edge(int id, double fromLon, double toLon) {
        EdgeIteratorState edge = mock(EdgeIteratorState.class);
        PointList geometry = new PointList();
        geometry.add(52.50, fromLon);
        geometry.add(52.50, toLon);
        when(edge.getEdge()).thenReturn(id);
        when(edge.fetchWayGeometry(FetchMode.ALL)).thenReturn(geometry);
        return edge;
    }

    private RideTracePoint point(double lat, double lon, long timestamp) {
        return new RideTracePoint(
                geometryFactory.createPoint(new Coordinate(lon, lat)), timestamp);
    }
}

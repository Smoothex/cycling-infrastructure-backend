package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.service.dto.api.CorridorGeometryDto;
import com.graphhopper.GraphHopper;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CorridorGeometryServiceTest {

    private final GraphHopperService graphHopperService = mock(GraphHopperService.class);
    private final GraphHopper hopper = mock(GraphHopper.class);
    private final BaseGraph graph = mock(BaseGraph.class);
    private final LocationIndex locationIndex = mock(LocationIndex.class);
    private final CorridorGeometryService service = new CorridorGeometryService(graphHopperService);

    @BeforeEach
    void setUpGraph() {
        when(graphHopperService.getHopper()).thenReturn(hopper);
        when(hopper.getBaseGraph()).thenReturn(graph);
        when(hopper.getLocationIndex()).thenReturn(locationIndex);
    }

    @Test
    void returnsSameNamedEdgesIntersectingTheBufferedCorridor() {
        doAnswer(invocation -> {
            LocationIndex.Visitor visitor = invocation.getArgument(1);
            visitor.onEdge(10);
            visitor.onEdge(11);
            visitor.onEdge(12);
            visitor.onEdge(13);
            visitor.onEdge(10);
            return null;
        }).when(locationIndex).query(any(com.graphhopper.util.shapes.BBox.class), any(LocationIndex.Visitor.class));

        EdgeIteratorState firstStreetEdge = edge(
                "Schönhauser Allee", points(52.5200, 13.4000, 52.5250, 13.4050));
        EdgeIteratorState secondStreetEdge = edge(
                " schönhauser allee ", points(52.5250, 13.4050, 52.5300, 13.4100));
        EdgeIteratorState differentStreetEdge = edge(
                "Prenzlauer Allee", points(52.5200, 13.4000, 52.5300, 13.4100));
        EdgeIteratorState distantStreetEdge = edge(
                "Schönhauser Allee", points(52.7000, 13.7000, 52.7100, 13.7100));
        when(graph.getEdgeIteratorState(10, Integer.MIN_VALUE)).thenReturn(firstStreetEdge);
        when(graph.getEdgeIteratorState(11, Integer.MIN_VALUE)).thenReturn(secondStreetEdge);
        when(graph.getEdgeIteratorState(12, Integer.MIN_VALUE)).thenReturn(differentStreetEdge);
        when(graph.getEdgeIteratorState(13, Integer.MIN_VALUE)).thenReturn(distantStreetEdge);

        CorridorGeometryDto result = service.getCorridorGeometry(
                "Schönhauser Allee", 13.399, 52.519, 13.411, 52.531);

        assertThat(result.streetName()).isEqualTo("Schönhauser Allee");
        assertThat(result.segmentIds()).containsExactly(10L, 11L);
        assertThat(result.geometry().type()).isEqualTo("MultiLineString");
        assertThat(result.geometry().coordinates()).hasSize(2);
        assertThat(result.geometry().coordinates().getFirst().getFirst())
                .containsExactly(13.4, 52.52);
    }

    @Test
    void rejectsInvalidOrExcessivelyLargeBounds() {
        assertThatThrownBy(() -> service.getCorridorGeometry(
                "Schönhauser Allee", 13.5, 52.5, 13.4, 52.6))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("bounds are invalid");

        assertThatThrownBy(() -> service.getCorridorGeometry(
                "Schönhauser Allee", 13.0, 52.0, 13.5, 52.1))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("bounds are too large");
    }

    private EdgeIteratorState edge(String name, PointList points) {
        EdgeIteratorState edge = mock(EdgeIteratorState.class);
        when(edge.getName()).thenReturn(name);
        when(edge.fetchWayGeometry(FetchMode.ALL)).thenReturn(points);
        return edge;
    }

    private PointList points(double firstLat, double firstLon, double secondLat, double secondLon) {
        PointList points = new PointList(2, false);
        points.add(firstLat, firstLon);
        points.add(secondLat, secondLon);
        return points;
    }
}

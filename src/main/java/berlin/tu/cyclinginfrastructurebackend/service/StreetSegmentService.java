package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.BBox;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class StreetSegmentService {
    private static final Logger log = LoggerFactory.getLogger(StreetSegmentService.class);
    private final StreetSegmentRepository repository;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    public StreetSegmentService(StreetSegmentRepository repository) {
        this.repository = repository;
    }

    public void updateUsage(EdgeIteratorState edge, GraphHopperService hopperService) {
        long edgeId = edge.getEdge();
        int updated = repository.incrementUsage(edgeId);

        // If it didn't exist, use the native UPSERT
        if (updated == 0) {
            String name = edge.getName();
            if (name == null || name.isBlank()) {
                name = findNearestStreetName(edge, hopperService);
            }

            PointList points = edge.fetchWayGeometry(FetchMode.ALL);
            Coordinate[] coords = new Coordinate[points.size()];
            for (int i = 0; i < points.size(); i++) {
                coords[i] = new Coordinate(points.getLon(i), points.getLat(i));
            }

            repository.upsertSegment(edgeId,
                    name != null ? name : "Unknown",
                    geometryFactory.createLineString(coords));

            // Increment now that it definitely exists
            repository.incrementUsage(edgeId);
        }
    }

    private String findNearestStreetName(EdgeIteratorState sourceEdge, GraphHopperService hopperService) {
        PointList points = sourceEdge.fetchWayGeometry(FetchMode.ALL);
        if (points.isEmpty()) return null;

        // Center point of the source edge to use as reference
        double lat = points.getLat(points.size() / 2);
        double lon = points.getLon(points.size() / 2);
        double radius = 0.0002;
        BBox bbox = new BBox(lon - radius, lon + radius, lat - radius, lat + radius);

        final String[] bestName = {null};
        final double[] minDistance = {Double.MAX_VALUE};
        final DistanceCalcEarth distCalc = new DistanceCalcEarth();

        hopperService.getHopper().getLocationIndex().query(bbox, edgeId -> {
            if (edgeId == sourceEdge.getEdge()) return;

            EdgeIteratorState candidate = hopperService.getHopper().getBaseGraph().getEdgeIteratorState(edgeId, Integer.MIN_VALUE);

            if (candidate != null && candidate.getName() != null && !candidate.getName().isBlank()) {
                PointList candidatePoints = candidate.fetchWayGeometry(FetchMode.ALL);
                if (candidatePoints.isEmpty()) return;

                // Calculate distance to the first point of the candidate edge
                double dist = distCalc.calcDist(lat, lon, candidatePoints.getLat(0), candidatePoints.getLon(0));

                if (dist < minDistance[0]) {
                    minDistance[0] = dist;
                    bestName[0] = candidate.getName();
                }
            }
        });
        return bestName[0];
    }
}
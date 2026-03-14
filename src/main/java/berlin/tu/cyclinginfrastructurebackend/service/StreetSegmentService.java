package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.SegmentAvoidance;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentAvoidanceRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class StreetSegmentService {
    private static final Logger log = LoggerFactory.getLogger(StreetSegmentService.class);
    private final StreetSegmentRepository repository;
    private final SegmentAvoidanceRepository avoidanceRepository;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    public StreetSegmentService(StreetSegmentRepository repository,
                                SegmentAvoidanceRepository avoidanceRepository) {
        this.repository = repository;
        this.avoidanceRepository = avoidanceRepository;
    }

    public void updateUsage(EdgeIteratorState edge, GraphHopperService hopperService) {
        long edgeId = edge.getEdge();
        int updated = repository.incrementUsage(edgeId);

        if (updated == 0) {
            String name = resolveEdgeName(edge, hopperService);
            PointList points = edge.fetchWayGeometry(FetchMode.ALL);
            Coordinate[] coords = toCoordinates(points);

            repository.upsertSegment(edgeId, name, geometryFactory.createLineString(coords));
            repository.incrementUsage(edgeId);
        }
    }

    /**
     * Batch-register avoided edges: upserts missing segments, increments avoidance counts,
     * and creates temporal SegmentAvoidance records for later correlation with external factors.
     * Processes edge IDs in sorted (ascending) order to maintain consistent lock ordering
     * across concurrent transactions, preventing deadlocks.
     */
    @Transactional
    public void registerAvoidedEdges(Collection<Integer> edgeIds, Ride ride, GraphHopperService hopperService) {
        if (edgeIds == null || edgeIds.isEmpty()) return;

        // Sort to avoid deadlocks
        List<Integer> sortedEdgeIds = new ArrayList<>(edgeIds);
        Collections.sort(sortedEdgeIds);

        Long avoidedAt = ride.getStartTime() != null
                ? ride.getStartTime()
                : System.currentTimeMillis();

        for (Integer edgeId : sortedEdgeIds) {
            ensureSegmentExists(edgeId, hopperService);
        }

        Set<Long> longIds = sortedEdgeIds.stream().map(Integer::longValue).collect(Collectors.toSet());
        repository.bulkIncrementAvoidance(longIds);

        // Create temporal avoidance records
        List<SegmentAvoidance> avoidanceRecords = new ArrayList<>();
        for (Integer edgeId : sortedEdgeIds) {
            StreetSegment segment = repository.getReferenceById(edgeId.longValue());
            avoidanceRecords.add(SegmentAvoidance.of(segment, ride, avoidedAt));
        }
        avoidanceRepository.saveAll(avoidanceRecords);
    }

    public void ensureSegmentExists(int edgeId, GraphHopperService hopperService) {
        EdgeIteratorState edge = hopperService.getHopper().getBaseGraph()
                .getEdgeIteratorState(edgeId, Integer.MIN_VALUE);

        if (edge != null) {
            String name = resolveEdgeName(edge, hopperService);
            PointList points = edge.fetchWayGeometry(FetchMode.ALL);
            Coordinate[] coords = toCoordinates(points);

            if (coords.length >= 2) {
                repository.upsertSegment((long) edgeId, name, geometryFactory.createLineString(coords));
            }
        }
    }

    private String resolveEdgeName(EdgeIteratorState edge, GraphHopperService hopperService) {
        String name = edge.getName();
        if (name == null || name.isBlank()) {
            name = findNearestStreetName(edge, hopperService);
        }
        return name != null ? name : "Unknown";
    }

    private Coordinate[] toCoordinates(PointList points) {
        Coordinate[] coords = new Coordinate[points.size()];
        for (int i = 0; i < points.size(); i++) {
            coords[i] = new Coordinate(points.getLon(i), points.getLat(i));
        }
        return coords;
    }

    private String findNearestStreetName(EdgeIteratorState sourceEdge, GraphHopperService hopperService) {
        PointList points = sourceEdge.fetchWayGeometry(FetchMode.ALL);
        if (points.isEmpty()) return null;

        double lat = points.getLat(points.size() / 2);
        double lon = points.getLon(points.size() / 2);
        double radius = 0.0003;
        BBox bbox = new BBox(lon - radius, lon + radius, lat - radius, lat + radius);

        final String[] bestName = {null};
        final double[] minDistance = {Double.MAX_VALUE};
        final DistanceCalcEarth distCalc = new DistanceCalcEarth();

        hopperService.getHopper().getLocationIndex().query(bbox, candidateEdgeId -> {
            if (candidateEdgeId == sourceEdge.getEdge()) return;

            EdgeIteratorState candidate = hopperService.getHopper().getBaseGraph()
                    .getEdgeIteratorState(candidateEdgeId, Integer.MIN_VALUE);

            if (candidate != null && candidate.getName() != null && !candidate.getName().isBlank()) {
                PointList candidatePoints = candidate.fetchWayGeometry(FetchMode.ALL);
                if (candidatePoints.isEmpty()) return;

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
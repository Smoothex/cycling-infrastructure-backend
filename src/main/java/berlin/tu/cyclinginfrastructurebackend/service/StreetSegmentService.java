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
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

@Service
public class StreetSegmentService {
    private final StreetSegmentRepository repository;
    private final TransactionTemplate segmentCreationTransactionTemplate;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    public StreetSegmentService(StreetSegmentRepository repository,
                                PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.segmentCreationTransactionTemplate = new TransactionTemplate(transactionManager);
        this.segmentCreationTransactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void ensureSegmentsExist(Collection<Integer> edgeIds, GraphHopperService hopperService) {
        if (edgeIds == null || edgeIds.isEmpty()) {
            return;
        }

        List<Integer> sortedEdgeIds = edgeIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (sortedEdgeIds.isEmpty()) {
            return;
        }

        Set<Long> existingIds = new HashSet<>(repository.findExistingIds(
                sortedEdgeIds.stream().map(Integer::longValue).toList()
        ));

        List<SegmentUpsert> missingSegments = sortedEdgeIds.stream()
                .filter(edgeId -> !existingIds.contains(edgeId.longValue()))
                .map(edgeId -> buildSegmentUpsert(edgeId, hopperService))
                .flatMap(Optional::stream)
                .toList();

        if (missingSegments.isEmpty()) {
            return;
        }

        // Keep reference creation isolated from the later final ride transaction. If later
        // analysis fails, an unused segment with zero counters is harmless and reusable.
        segmentCreationTransactionTemplate.executeWithoutResult(status -> {
            for (SegmentUpsert segment : missingSegments) {
                repository.upsertSegment(segment.id(), segment.name(), segment.geometry(), segment.gradientPercent());
            }
        });
    }

    private Optional<SegmentUpsert> buildSegmentUpsert(int edgeId, GraphHopperService hopperService) {
        EdgeIteratorState edge = hopperService.getHopper().getBaseGraph()
                .getEdgeIteratorState(edgeId, Integer.MIN_VALUE);

        if (edge == null) {
            return Optional.empty();
        }

        String name = resolveEdgeName(edge, hopperService);
        PointList points = edge.fetchWayGeometry(FetchMode.ALL);
        Coordinate[] coords = toCoordinates(points);
        if (coords.length < 2) {
            return Optional.empty();
        }

        Double gradient = hopperService.getGradientPercent(edgeId);
        return Optional.of(new SegmentUpsert(
                (long) edgeId,
                name,
                geometryFactory.createLineString(coords),
                gradient
        ));
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

    private record SegmentUpsert(
            Long id,
            String name,
            org.locationtech.jts.geom.LineString geometry,
            Double gradientPercent
    ) {
    }
}

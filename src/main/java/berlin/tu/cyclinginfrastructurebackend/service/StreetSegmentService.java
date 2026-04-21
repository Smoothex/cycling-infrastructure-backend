package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEventType;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentEventRepository;
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
    private final SegmentEventRepository segmentEventRepository;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    public StreetSegmentService(StreetSegmentRepository repository,
                                SegmentEventRepository segmentEventRepository) {
        this.repository = repository;
        this.segmentEventRepository = segmentEventRepository;
    }

    public void updateUsage(EdgeIteratorState edge, GraphHopperService hopperService) {
        long edgeId = edge.getEdge();
        int updated = repository.incrementUsage(edgeId);

        if (updated == 0) {
            String name = resolveEdgeName(edge, hopperService);
            PointList points = edge.fetchWayGeometry(FetchMode.ALL);
            Coordinate[] coords = toCoordinates(points);
            Double gradient = hopperService.getGradientPercent((int) edgeId);

            repository.upsertSegment(edgeId, name, geometryFactory.createLineString(coords), gradient);
            repository.incrementUsage(edgeId);
        }
    }

    @Transactional
    public void registerSegmentEvents(Map<Integer, Double> avoidedEdgeBearings,
                                      Map<Integer, Double> chosenEdgeBearings,
                                      Ride ride,
                                      GraphHopperService hopperService) {
        boolean hasAvoidedEdges = avoidedEdgeBearings != null && !avoidedEdgeBearings.isEmpty();
        boolean hasChosenEdges = chosenEdgeBearings != null && !chosenEdgeBearings.isEmpty();
        if (!hasAvoidedEdges && !hasChosenEdges) return;

        Set<Integer> allEdgeIds = new HashSet<>();
        if (hasAvoidedEdges) {
            allEdgeIds.addAll(avoidedEdgeBearings.keySet());
        }
        if (hasChosenEdges) {
            allEdgeIds.addAll(chosenEdgeBearings.keySet());
        }

        List<Integer> sortedEdgeIds = new ArrayList<>(allEdgeIds);
        Collections.sort(sortedEdgeIds);

        Long eventTimestamp = ride.getStartTime() != null
                ? ride.getStartTime()
                : System.currentTimeMillis();

        for (Integer edgeId : sortedEdgeIds) {
            ensureSegmentExists(edgeId, hopperService);
        }

        if (hasAvoidedEdges) {
            Set<Long> avoidedIds = avoidedEdgeBearings.keySet().stream()
                    .map(Integer::longValue)
                    .collect(Collectors.toSet());
            repository.bulkIncrementAvoidance(avoidedIds);
        }

        if (hasChosenEdges) {
            Set<Long> chosenIds = chosenEdgeBearings.keySet().stream()
                    .map(Integer::longValue)
                    .collect(Collectors.toSet());
            repository.bulkIncrementPreference(chosenIds);
        }

        List<SegmentEvent> eventRecords = new ArrayList<>();
        for (Integer edgeId : sortedEdgeIds) {
            StreetSegment segment = repository.getReferenceById(edgeId.longValue());

            if (hasAvoidedEdges && avoidedEdgeBearings.containsKey(edgeId)) {
                eventRecords.add(SegmentEvent.of(
                        SegmentEventType.AVOIDANCE,
                        segment,
                        ride,
                        eventTimestamp,
                        avoidedEdgeBearings.get(edgeId)
                ));
            }

            if (hasChosenEdges && chosenEdgeBearings.containsKey(edgeId)) {
                eventRecords.add(SegmentEvent.of(
                        SegmentEventType.PREFERENCE,
                        segment,
                        ride,
                        eventTimestamp,
                        chosenEdgeBearings.get(edgeId)
                ));
            }
        }

        segmentEventRepository.saveAll(eventRecords);
    }

    public void ensureSegmentExists(int edgeId, GraphHopperService hopperService) {
        EdgeIteratorState edge = hopperService.getHopper().getBaseGraph()
                .getEdgeIteratorState(edgeId, Integer.MIN_VALUE);

        if (edge != null) {
            String name = resolveEdgeName(edge, hopperService);
            PointList points = edge.fetchWayGeometry(FetchMode.ALL);
            Coordinate[] coords = toCoordinates(points);
            Double gradient = hopperService.getGradientPercent(edgeId);

            if (coords.length >= 2) {
                repository.upsertSegment((long) edgeId, name, geometryFactory.createLineString(coords), gradient);
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

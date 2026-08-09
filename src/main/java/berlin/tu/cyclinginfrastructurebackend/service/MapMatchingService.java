package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.util.BearingCalculator;
import com.graphhopper.matching.EdgeMatch;
import com.graphhopper.matching.MatchResult;
import com.graphhopper.matching.Observation;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class MapMatchingService {
    private static final Logger log = LoggerFactory.getLogger(MapMatchingService.class);

    private final GraphHopperService hopperService;
    private final StreetSegmentService segmentService;
    private final double minimumOriginDestinationDistanceMeters;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    public MapMatchingService(GraphHopperService hopperService,
                              StreetSegmentService segmentService,
                              @Value("${analysis.minimum-origin-destination-distance-meters:500}")
                              double minimumOriginDestinationDistanceMeters) {
        this.hopperService = hopperService;
        this.segmentService = segmentService;
        this.minimumOriginDestinationDistanceMeters = minimumOriginDestinationDistanceMeters;
    }

    /**
     * Produces the one canonical trace used by validation, map matching, timestamp assignment,
     * and detour endpoint selection.
     */
    public List<RideTracePoint> canonicalizeTrace(List<RideTracePoint> trace) {
        return trace.stream()
                .filter(point -> point.location() != null && point.timestamp() != null)
                .filter(point -> isValidCoordinate(point.location().getY(), point.location().getX()))
                .sorted(Comparator.comparingLong(RideTracePoint::timestamp))
                .toList();
    }

    /** Prepares all map-matched ride state and usage deltas without persisting ride state. */
    public RideProcessingResult processRide(Ride ride, List<RideTracePoint> canonicalTrace) {
        long totalStartedAt = System.nanoTime();
        long graphHopperNanos = 0;
        long timestampCalculationNanos = 0;
        long segmentPreparationNanos = 0;
        try {
            if (canonicalTrace.size() < 2) {
                ride.setStatus(Status.SKIPPED);
                return result(true, Map.of(), totalStartedAt, graphHopperNanos,
                        timestampCalculationNanos, segmentPreparationNanos);
            }

            double originDestinationDistanceMeters = calculateOriginDestinationDistanceMeters(canonicalTrace);
            if (originDestinationDistanceMeters < minimumOriginDestinationDistanceMeters) {
                log.debug(
                        "Skipping ride {}: origin-destination distance {} m is below the minimum of {} m",
                        ride.getOriginalFilename(), originDestinationDistanceMeters,
                        minimumOriginDestinationDistanceMeters);
                ride.setStatus(Status.SKIPPED);
                return result(true, Map.of(), totalStartedAt, graphHopperNanos,
                        timestampCalculationNanos, segmentPreparationNanos);
            }

            List<Observation> observations = canonicalTrace.stream()
                    .map(point -> new Observation(new GHPoint(
                            point.location().getY(), point.location().getX())))
                    .toList();

            MatchResult matchResult;
            long graphHopperStartedAt = System.nanoTime();
            try {
                matchResult = hopperService.match(observations);
            } finally {
                graphHopperNanos = System.nanoTime() - graphHopperStartedAt;
            }

            ride.setActualDistance(matchResult.getMatchLength());
            updateRideTrajectory(ride, matchResult);

            List<EdgeMatch> edgeMatches = matchResult.getEdgeMatches();
            List<EdgeIteratorState> edges = edgeMatches.stream()
                    .map(EdgeMatch::getEdgeState)
                    .toList();
            ride.setTraversedEdgeIds(edges.stream().map(EdgeIteratorState::getEdge).toList());
            ride.setTraversedEdgeBearings(computeEdgeBearings(edgeMatches));

            long timestampStartedAt = System.nanoTime();
            try {
                ride.setTraversedEdgeTimestamps(computeEdgeTimestamps(edgeMatches, canonicalTrace));
            } finally {
                timestampCalculationNanos = System.nanoTime() - timestampStartedAt;
            }

            Map<Long, Integer> usageByEdgeId = aggregateUsage(edges);
            long segmentPreparationStartedAt = System.nanoTime();
            try {
                segmentService.ensureSegmentsExist(
                        usageByEdgeId.keySet().stream().map(Long::intValue).toList(), hopperService);
            } finally {
                segmentPreparationNanos = System.nanoTime() - segmentPreparationStartedAt;
            }

            return result(true, usageByEdgeId, totalStartedAt, graphHopperNanos,
                    timestampCalculationNanos, segmentPreparationNanos);
        } catch (Exception e) {
            log.error("Failed to map-match ride {}", ride.getOriginalFilename(), e);
            return result(false, Map.of(), totalStartedAt, graphHopperNanos,
                    timestampCalculationNanos, segmentPreparationNanos);
        }
    }

    private RideProcessingResult result(boolean success,
                                        Map<Long, Integer> usageByEdgeId,
                                        long totalStartedAt,
                                        long graphHopperNanos,
                                        long timestampCalculationNanos,
                                        long segmentPreparationNanos) {
        return new RideProcessingResult(
                success,
                usageByEdgeId,
                System.nanoTime() - totalStartedAt,
                graphHopperNanos,
                timestampCalculationNanos,
                segmentPreparationNanos
        );
    }

    private Map<Long, Integer> aggregateUsage(List<EdgeIteratorState> edges) {
        Map<Long, Integer> usageByEdgeId = new TreeMap<>();
        for (EdgeIteratorState edge : edges) {
            usageByEdgeId.merge((long) edge.getEdge(), 1, Integer::sum);
        }
        return usageByEdgeId;
    }

    private void updateRideTrajectory(Ride ride, MatchResult result) {
        List<Coordinate> allCoords = new ArrayList<>();
        List<EdgeMatch> matches = result.getEdgeMatches();

        for (int i = 0; i < matches.size(); i++) {
            PointList points = matches.get(i).getEdgeState().fetchWayGeometry(FetchMode.ALL);
            for (int j = 0; j < points.size(); j++) {
                if (i > 0 && j == 0) {
                    continue;
                }
                allCoords.add(new Coordinate(points.getLon(j), points.getLat(j)));
            }
        }

        if (allCoords.size() >= 2) {
            ride.setTrajectory(geometryFactory.createLineString(allCoords.toArray(Coordinate[]::new)));
        }
    }

    private boolean isValidCoordinate(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
                && lat != 0.0 && lon != 0.0;
    }

    private double calculateOriginDestinationDistanceMeters(List<RideTracePoint> sortedPoints) {
        RideTracePoint origin = sortedPoints.getFirst();
        RideTracePoint destination = sortedPoints.getLast();
        return DistanceCalcEarth.DIST_EARTH.calcDist(
                origin.location().getY(), origin.location().getX(),
                destination.location().getY(), destination.location().getX());
    }

    private Map<Integer, Double> computeEdgeBearings(List<EdgeMatch> edgeMatches) {
        Map<Integer, Double> bearings = new LinkedHashMap<>();
        for (EdgeMatch match : edgeMatches) {
            EdgeIteratorState edgeState = match.getEdgeState();
            int edgeId = edgeState.getEdge();
            if (bearings.containsKey(edgeId)) {
                continue;
            }

            PointList geometry = edgeState.fetchWayGeometry(FetchMode.ALL);
            if (geometry == null || geometry.size() < 2) {
                bearings.put(edgeId, null);
                continue;
            }
            bearings.put(edgeId, BearingCalculator.calculateBearing(
                    geometry, 0, geometry.size() - 1));
        }
        return bearings;
    }

    private Map<Integer, Long> computeEdgeTimestamps(List<EdgeMatch> edgeMatches,
                                                      List<RideTracePoint> trace) {
        Map<Integer, Long> timestamps = new LinkedHashMap<>();
        DistanceCalcEarth distanceCalculator = new DistanceCalcEarth();

        for (EdgeMatch match : edgeMatches) {
            EdgeIteratorState edgeState = match.getEdgeState();
            int edgeId = edgeState.getEdge();
            if (timestamps.containsKey(edgeId)) {
                continue;
            }

            PointList geometry = edgeState.fetchWayGeometry(FetchMode.ALL);
            if (geometry == null || geometry.isEmpty()) {
                timestamps.put(edgeId, null);
                continue;
            }

            int midpoint = geometry.size() / 2;
            double edgeLat = geometry.getLat(midpoint);
            double edgeLon = geometry.getLon(midpoint);
            RideTracePoint closestPoint = null;
            double minimumDistance = Double.MAX_VALUE;

            for (RideTracePoint point : trace) {
                double distance = distanceCalculator.calcDist(
                        edgeLat, edgeLon, point.location().getY(), point.location().getX());
                if (distance < minimumDistance) {
                    minimumDistance = distance;
                    closestPoint = point;
                }
            }
            timestamps.put(edgeId, closestPoint != null ? closestPoint.timestamp() : null);
        }
        return timestamps;
    }
}

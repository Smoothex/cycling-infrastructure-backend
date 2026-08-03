package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.RidePoint;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import berlin.tu.cyclinginfrastructurebackend.util.BearingCalculator;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import com.graphhopper.util.details.PathDetail;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.WKTWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

@Service
public class DetourAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(DetourAnalysisService.class);

    private final GraphHopperService graphHopperService;
    private final RideRepository rideRepository;
    private final StreetSegmentService streetSegmentService;
    private final StreetSegmentRepository streetSegmentRepository;
    private final RideIntentClassifier rideIntentClassifier;
    private final TransactionTemplate transactionTemplate;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    private final WKTWriter wktWriter = new WKTWriter();

    @Value("${analysis.detour.threshold}")
    private double detourThreshold;

    @Value("${analysis.spatial.proximity-meters}")
    private double proximityMeters;

    public DetourAnalysisService(GraphHopperService graphHopperService,
                                 RideRepository rideRepository,
                                 StreetSegmentService streetSegmentService,
                                 StreetSegmentRepository streetSegmentRepository,
                                 RideIntentClassifier rideIntentClassifier,
                                 PlatformTransactionManager transactionManager) {
        this.graphHopperService = graphHopperService;
        this.rideRepository = rideRepository;
        this.streetSegmentService = streetSegmentService;
        this.streetSegmentRepository = streetSegmentRepository;
        this.rideIntentClassifier = rideIntentClassifier;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Status analyzeRide(UUID rideId) {
        Status result = transactionTemplate.execute(txStatus -> {
            Ride ride = rideRepository.findById(rideId).orElse(null);
            if (ride == null) {
                log.warn("Ride with ID {} not found during analysis.", rideId);
                return Status.ERROR;
            }

            try {
                return analyzeLoadedRide(ride);
            } catch (Exception e) {
                log.error("Failed to analyze ride {}", rideId, e);
                txStatus.setRollbackOnly();
                return Status.ERROR;
            }
        });

        if (result == Status.ERROR) {
            rideRepository.updateStatus(rideId, Status.ERROR);
        }
        return result;
    }

    private Status analyzeLoadedRide(Ride ride) {
        List<RidePoint> points = ride.getRidePoints().stream()
                .filter(p -> p.getLocation() != null)
                .sorted(Comparator.comparingLong(RidePoint::getTimestamp))
                .toList();

        if (points.size() < 2 || ride.getTraversedEdgeIds().isEmpty() || ride.getTrajectory() == null) {
            ride.setStatus(Status.SKIPPED);
            return Status.SKIPPED;
        }

        RidePoint start = points.getFirst();
        RidePoint end = points.getLast();

        ResponsePath shortestPath = graphHopperService.getShortestPath(
                start.getLocation().getY(), start.getLocation().getX(),
                end.getLocation().getY(), end.getLocation().getX()
        );

        if (shortestPath == null) {
            ride.setStatus(Status.SKIPPED);
            return Status.SKIPPED;
        }

        Set<Integer> shortestEdges = extractEdgeIds(shortestPath);
        Set<Integer> actualEdges = new HashSet<>(ride.getTraversedEdgeIds());

        // Build shortest-path geometry and persist (can be removed in the future)
        PointList ghPoints = shortestPath.getPoints();
        Coordinate[] coords = new Coordinate[ghPoints.size()];
        for (int i = 0; i < ghPoints.size(); i++) {
            coords[i] = new Coordinate(ghPoints.getLon(i), ghPoints.getLat(i));
        }
        LineString shortestPathGeometry = geometryFactory.createLineString(coords);

        ride.setShortestPath(shortestPathGeometry);
        ride.setShortestPathEdgeIds(new ArrayList<>(shortestEdges));

        double shortestPathDistance = shortestPath.getDistance();
        double actualDistance = ride.getActualDistance();

        ride.setShortestPathDistance(shortestPathDistance);
        ride.setActualDistance(actualDistance);

        boolean isDetour = actualDistance > shortestPathDistance * (1.0 + detourThreshold);
        ride.setIsDetour(isDetour);

        if (isDetour) {
            Set<Integer> allEdges = new HashSet<>(shortestEdges);
            allEdges.addAll(actualEdges);

            ensureEdgesExist(allEdges);

            LineString actualTrajectory = ride.getTrajectory();
            Set<Integer> avoidedEdges = filterSpatiallyDistantEdges(
                    shortestEdges, actualEdges, actualTrajectory);

            double overlapRatio = shortestEdges.isEmpty() ? 1.0
                    : (double) (shortestEdges.size() - avoidedEdges.size()) / shortestEdges.size();
            ride.setOverlapRatio(overlapRatio);

            if (isAlternativeRoute(shortestEdges, avoidedEdges, 0.30)) {
                log.debug("Ride {} is an ALTERNATIVE ROUTE (overlap < 30%). Skipping edge registration.", ride.getId());
                ride.setStatus(Status.ALTERNATIVE_ROUTE);
                rideIntentClassifier.classify(ride);
                return Status.ALTERNATIVE_ROUTE;
            }

            Set<Integer> chosenEdges = filterSpatiallyDistantEdges(
                    actualEdges, shortestEdges, shortestPathGeometry);

            Map<Integer, Double> avoidedEdgeBearings = buildEdgeBearingsFromShortestPath(
                    shortestPath,
                    avoidedEdges);
            Map<Integer, Long> avoidedEdgeTimestamps = computeAvoidedEdgeTimestamps(avoidedEdges, ride, points);

            // Use pre-computed bearings from map matching instead of inferring direction
            Map<Integer, Double> chosenEdgeBearings = filterEdgeBearings(
                    ride.getTraversedEdgeBearings(),
                    chosenEdges
            );
            Map<Integer, Long> chosenEdgeTimestamps = filterEdgeTimestamps(
                    ride.getTraversedEdgeTimestamps(),
                    chosenEdges
            );

            ride.setStatus(Status.PROCESSED);
            rideIntentClassifier.classify(ride);

            streetSegmentService.registerSegmentEvents(
                    avoidedEdgeBearings,
                    avoidedEdgeTimestamps,
                    chosenEdgeBearings,
                    chosenEdgeTimestamps,
                    ride,
                    graphHopperService
            );

            return Status.PROCESSED;
        }

        int sharedEdges = 0;
        for (Integer edgeId : shortestEdges) {
            if (actualEdges.contains(edgeId))
                sharedEdges++;
        }
        double overlapRatio = shortestEdges.isEmpty() ? 1.0
                : (double) sharedEdges / shortestEdges.size();
        ride.setOverlapRatio(overlapRatio);

        ride.setStatus(Status.PROCESSED);
        rideIntentClassifier.classify(ride);

        return Status.PROCESSED;
    }

    private Set<Integer> extractEdgeIds(ResponsePath path) {
        Set<Integer> edges = new HashSet<>();
        List<PathDetail> edgeDetails = path.getPathDetails().get("edge_id");
        if (edgeDetails != null) {
            for (PathDetail detail : edgeDetails) {
                edges.add((Integer) detail.getValue());
            }
        }
        return edges;
    }

    /**
     * Ensures all edges exist in street_segments so PostGIS ST_DWithin queries can work.
     * Processes edge IDs in sorted (ascending) order to prevent deadlocks.
     */
    private void ensureEdgesExist(Set<Integer> edgeIds) {
        streetSegmentService.ensureSegmentsExist(edgeIds, graphHopperService);
    }

    /**
     * Filters source edges to find those physically distant from a reference path.
     * Uses PostGIS ST_DWithin (meters) to solve the "parallel edge" problem — segregated
     * cycle paths or opposite-direction edges get different IDs but are spatially close.
     *
     * @param sourceEdges       Edges to evaluate (e.g., shortest-path edges)
     * @param referenceEdges    Edges to skip (shared between both paths)
     * @param referenceGeometry The physical path to measure against
     * @return Edge IDs that are genuinely spatially divergent from the reference path
     */
    private Set<Integer> filterSpatiallyDistantEdges(Set<Integer> sourceEdges,
                                                     Set<Integer> referenceEdges,
                                                     LineString referenceGeometry) {
        Set<Integer> filteredEdges = new HashSet<>();
        String referenceWkt = wktWriter.write(referenceGeometry);
        List<Long> candidateEdgeIds = sourceEdges.stream()
                .filter(edgeId -> !referenceEdges.contains(edgeId))
                .map(Integer::longValue)
                .toList();

        if (candidateEdgeIds.isEmpty()) {
            return filteredEdges;
        }

        Set<Long> closeEdgeIds = new HashSet<>(streetSegmentRepository.findEdgeIdsWithinDistance(
                candidateEdgeIds,
                referenceWkt,
                proximityMeters
        ));

        for (Long edgeId : candidateEdgeIds) {
            if (!closeEdgeIds.contains(edgeId)) {
                filteredEdges.add(edgeId.intValue());
            }
        }
        return filteredEdges;
    }

    /**
     * Determines if the cyclist took a completely different path (an alternative route)
     * rather than making a local detour along the shortest path. This is calculated by
     * checking the ratio of shared edges vs. total shortest path edges.
     *
     * @param shortestEdges    The set of all edge IDs in the theoretical shortest path
     * @param avoidedEdges     The set of shortest path edges that were physically distant from the actual ride
     * @param minOverlapRatio  The minimum required overlap (e.g., 0.3 for 30%) to be considered the same general route
     * @return                 True if the overlap is below the threshold, indicating an alternative route
     */
    private boolean isAlternativeRoute(Set<Integer> shortestEdges,
                                       Set<Integer> avoidedEdges,
                                       double minOverlapRatio) {
        if (shortestEdges.isEmpty()) return false;

        int overlappingEdgesCount = shortestEdges.size() - avoidedEdges.size();
        double overlapRatio = (double) overlappingEdgesCount / shortestEdges.size();

        log.debug("Route overlap ratio: {} ({} overlapping / {} total shortest path edges)",
                String.format("%.2f", overlapRatio), overlappingEdgesCount, shortestEdges.size());

        return overlapRatio < minOverlapRatio;
    }

    /**
     * Builds one compass bearing per avoided edge on the shortest path.
     * <p>
     * The returned bearing describes the direction the rider would have moved
     * along that edge. Calculated per avoided edge and not per street segment
     * because the same street can be used in opposite directions in different rides.
     *
     * @param path    the shortest path returned by GraphHopper, including edge details and points
     * @param edgeIds the avoided edge ids for which a bearing is needed
     * @return a map from edge id to bearing in degrees; if a bearing cannot be derived,
     * the edge is still included with a {@code null} value
     */
    private Map<Integer, Double> buildEdgeBearingsFromShortestPath(ResponsePath path, Set<Integer> edgeIds) {
        if (path == null || edgeIds == null || edgeIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<PathDetail> edgeDetails = path.getPathDetails().get("edge_id");
        PointList points = path.getPoints();
        if (edgeDetails == null || edgeDetails.isEmpty() || points == null || points.size() < 2) {
            return Collections.emptyMap();
        }

        Map<Integer, Double> bearings = new LinkedHashMap<>();
        for (PathDetail detail : edgeDetails) {
            if (!(detail.getValue() instanceof Integer edgeId) || !edgeIds.contains(edgeId) || bearings.containsKey(edgeId)) {
                continue;
            }

            bearings.put(edgeId, BearingCalculator.calculateBearing(points, detail.getFirst(), detail.getLast()));
        }

        for (Integer edgeId : edgeIds) {
            bearings.putIfAbsent(edgeId, null);
        }

        return bearings;
    }

    /**
     * Filters pre-computed edge bearings to include only the specified edge IDs.
     * <p>
     * Bearings are pre-computed in {@link MapMatchingService#processRide},
     * this method simply extracts the relevant subset of bearings for the chosen edges.
     *
     * @param allBearings the complete map of edge ID to bearing from the ride
     * @param edgeIds     the subset of edge IDs to include in the result
     * @return a map containing only the specified edges with their bearings;
     *         edges not found in allBearings will have null values
     */
    private Map<Integer, Double> filterEdgeBearings(Map<Integer, Double> allBearings, Set<Integer> edgeIds) {
        if (allBearings == null || edgeIds == null || edgeIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Integer, Double> result = new LinkedHashMap<>();
        for (Integer edgeId : edgeIds) {
            result.put(edgeId, allBearings.getOrDefault(edgeId, null));
        }
        return result;
    }

    /**
     * Filters edge timestamps to include only specified edge IDs.
     *
     * @param allTimestamps the complete map of edge ID to timestamp from the ride
     * @param edgeIds       the subset of edge IDs to include in the result
     * @return a map containing only the specified edges with their timestamps;
     *         edges not found in allTimestamps will have null values
     */
    private Map<Integer, Long> filterEdgeTimestamps(Map<Integer, Long> allTimestamps, Set<Integer> edgeIds) {
        if (allTimestamps == null || edgeIds == null || edgeIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Integer, Long> result = new LinkedHashMap<>();
        for (Integer edgeId : edgeIds) {
            result.put(edgeId, allTimestamps.getOrDefault(edgeId, null));
        }
        return result;
    }

    /**
     * Computes timestamps for avoided edges by finding the closest point on the actual trajectory.
     * Since these edges weren't traversed, this method estimates when the rider was nearest to them.
     * Distances are compared in normalized form (monotonic with real distance but without the
     * final asin/radius step), which is enough to pick the closest point.
     *
     * @param avoidedEdges the set of edge IDs that were avoided
     * @param ride         the ride containing trajectory and timestamps
     * @param sortedPoints ride points pre-filtered for location and sorted by timestamp
     * @return map of edge ID to estimated timestamp
     */
    private Map<Integer, Long> computeAvoidedEdgeTimestamps(Set<Integer> avoidedEdges,
                                                            Ride ride,
                                                            List<RidePoint> sortedPoints) {
        Map<Integer, Long> timestamps = new LinkedHashMap<>();
        DistanceCalcEarth distCalc = DistanceCalcEarth.DIST_EARTH;

        List<RidePoint> candidatePoints = sortedPoints.stream()
                .filter(p -> p.getTimestamp() != null)
                .toList();

        if (candidatePoints.isEmpty()) {
            // fallback start time
            for (Integer edgeId : avoidedEdges) {
                timestamps.put(edgeId, ride.getStartTime());
            }
            return timestamps;
        }

        var baseGraph = graphHopperService.getHopper().getBaseGraph();
        for (Integer edgeId : avoidedEdges) {
            EdgeIteratorState edge = baseGraph.getEdgeIteratorState(edgeId, Integer.MIN_VALUE);

            if (edge == null) {
                timestamps.put(edgeId, ride.getStartTime());
                continue;
            }

            PointList geometry = edge.fetchWayGeometry(FetchMode.ALL);
            if (geometry == null || geometry.isEmpty()) {
                timestamps.put(edgeId, ride.getStartTime());
                continue;
            }

            // Use edge midpoint
            int midIdx = geometry.size() / 2;
            double edgeLat = geometry.getLat(midIdx);
            double edgeLon = geometry.getLon(midIdx);

            // Find closest RidePoint
            RidePoint closestPoint = null;
            double minDistance = Double.MAX_VALUE;

            for (RidePoint point : candidatePoints) {
                double pointLat = point.getLocation().getY();
                double pointLon = point.getLocation().getX();
                double distance = distCalc.calcNormalizedDist(edgeLat, edgeLon, pointLat, pointLon);

                if (distance < minDistance) {
                    minDistance = distance;
                    closestPoint = point;
                }
            }

            timestamps.put(edgeId, closestPoint != null ? closestPoint.getTimestamp() : ride.getStartTime());
        }

        return timestamps;
    }
}

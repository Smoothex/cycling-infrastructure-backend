package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.RidePoint;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import berlin.tu.cyclinginfrastructurebackend.util.BearingCalculator;
import com.graphhopper.ResponsePath;
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
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class DetourAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(DetourAnalysisService.class);

    private final GraphHopperService graphHopperService;
    private final RideRepository rideRepository;
    private final StreetSegmentService streetSegmentService;
    private final StreetSegmentRepository streetSegmentRepository;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    private final WKTWriter wktWriter = new WKTWriter();

    @Value("${analysis.detour.threshold}")
    private double detourThreshold;

    @Value("${analysis.spatial.proximity-meters}")
    private double proximityMeters;

    public DetourAnalysisService(GraphHopperService graphHopperService,
                                 RideRepository rideRepository,
                                 StreetSegmentService streetSegmentService,
                                 StreetSegmentRepository streetSegmentRepository) {
        this.graphHopperService = graphHopperService;
        this.rideRepository = rideRepository;
        this.streetSegmentService = streetSegmentService;
        this.streetSegmentRepository = streetSegmentRepository;
    }

    @Transactional
    public Status analyzeRide(UUID rideId) {
        Ride ride = rideRepository.findById(rideId).orElse(null);
        if (ride == null) {
            log.warn("Ride with ID {} not found during analysis.", rideId);
            return Status.ERROR;
        }

        try {
            List<RidePoint> points = ride.getRidePoints().stream()
                    .filter(p -> p.getLocation() != null)
                    .sorted(Comparator.comparingLong(RidePoint::getTimestamp))
                    .toList();

            if (points.size() < 2 || ride.getTraversedEdgeIds().isEmpty() || ride.getTrajectory() == null) {
                return markAs(ride, Status.SKIPPED);
            }

            RidePoint start = points.getFirst();
            RidePoint end = points.getLast();

            ResponsePath shortestPath = graphHopperService.getShortestPath(
                    start.getLocation().getY(), start.getLocation().getX(),
                    end.getLocation().getY(), end.getLocation().getX()
            );

            if (shortestPath == null) {
                return markAs(ride, Status.SKIPPED);
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

                if (isAlternativeRoute(shortestEdges, avoidedEdges, 0.30)) {
                    log.info("Ride {} is an ALTERNATIVE ROUTE (overlap < 30%). Skipping edge registration.", ride.getId());
                    return markAs(ride, Status.ALTERNATIVE_ROUTE);
                }

                Set<Integer> chosenEdges = filterSpatiallyDistantEdges(
                        actualEdges, shortestEdges, shortestPathGeometry);

                Map<Integer, Double> avoidedEdgeBearings = buildEdgeBearingsFromShortestPath(
                        shortestPath,
                        avoidedEdges);
                // Use pre-computed bearings from map matching instead of inferring direction
                Map<Integer, Double> chosenEdgeBearings = filterEdgeBearings(
                        ride.getTraversedEdgeBearings(),
                        chosenEdges
                );
                streetSegmentService.registerSegmentEvents(
                        avoidedEdgeBearings,
                        chosenEdgeBearings,
                        ride,
                        graphHopperService
                );

                log.info("Ride {} identified as detour. Avoided {} edges, Chosen {} edges.",
                        ride.getId(), avoidedEdges.size(), chosenEdges.size());
            }

            return markAs(ride, Status.PROCESSED);

        } catch (Exception e) {
            log.error("Failed to analyze ride {}", rideId, e);
            return markAs(ride, Status.ERROR);
        }
    }

    private Status markAs(Ride ride, Status status) {
        ride.setStatus(status);
        rideRepository.save(ride);
        return status;
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
        List<Integer> sortedEdgeIds = new ArrayList<>(edgeIds);
        Collections.sort(sortedEdgeIds);

        for (Integer edgeId : sortedEdgeIds) {
            streetSegmentService.ensureSegmentExists(edgeId, graphHopperService);
        }
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

        for (Integer edgeId : sourceEdges) {
            if (!referenceEdges.contains(edgeId)) {
                boolean isClose = streetSegmentRepository.isEdgeWithinDistance(
                        edgeId, referenceWkt, proximityMeters);
                if (!isClose) {
                    filteredEdges.add(edgeId);
                }
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

        log.debug("Route overlap ratio: {} ({} overlapping / {} total shortest edges)",
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
     * This replaces the previous direction-inference logic that was needed when bearings
     * were not computed during map matching. Now that bearings are pre-computed in
     * {@link MapMatchingService#processRide}, this method simply extracts the relevant
     * subset of bearings for the chosen edges.
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
}

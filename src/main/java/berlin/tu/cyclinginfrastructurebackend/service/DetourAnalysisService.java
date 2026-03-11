package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.RidePoint;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
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

            double shortestPathDistance = shortestPath.getDistance();
            double actualDistance = ride.getActualDistance();

            ride.setShortestPathDistance(shortestPathDistance);
            ride.setActualDistance(actualDistance);

            boolean isDetour = actualDistance > shortestPathDistance * (1.0 + detourThreshold);
            ride.setIsDetour(isDetour);

            if (isDetour) {
                PointList ghPoints = shortestPath.getPoints();
                Coordinate[] coords = new Coordinate[ghPoints.size()];
                for (int i = 0; i < ghPoints.size(); i++) {
                    coords[i] = new Coordinate(ghPoints.getLon(i), ghPoints.getLat(i));
                }
                LineString shortestPathGeometry = geometryFactory.createLineString(coords);
                LineString actualTrajectory = ride.getTrajectory();

                // Ensure shortest-path edges exist in DB so ST_DWithin can query them
                ensureEdgesExist(shortestEdges);

                Set<Integer> avoidedEdges = filterSpatiallyDistantEdges(
                        shortestEdges, actualEdges, actualTrajectory);

                if (isAlternativeRoute(shortestEdges, avoidedEdges, 0.30)) {
                    log.info("Ride {} is an ALTERNATIVE ROUTE (overlap < 30%). Skipping edge registration.", ride.getId());
                    return markAs(ride, Status.ALTERNATIVE_ROUTE);
                }

                ensureEdgesExist(actualEdges);

                Set<Integer> chosenEdges = filterSpatiallyDistantEdges(
                        actualEdges, shortestEdges, shortestPathGeometry);

                ride.setAvoidedEdgeIds(new ArrayList<>(avoidedEdges));
                ride.setChosenEdgeIds(new ArrayList<>(chosenEdges));

                streetSegmentService.registerAvoidedEdges(avoidedEdges, ride, graphHopperService);

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
            if (!streetSegmentRepository.existsById(edgeId.longValue())) {
                LineString geom = graphHopperService.getEdgeGeometry(edgeId);
                if (geom != null && geom.getNumPoints() >= 2) {
                    streetSegmentRepository.upsertSegment(
                            edgeId.longValue(), "Unknown", geom.toText());
                }
            }
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
     * @param shortestEdges The set of all edge IDs in the theoretical shortest path.
     * @param avoidedEdges  The set of shortest path edges that were physically distant from the actual ride.
     * @param minOverlapRatio    The minimum required overlap (e.g., 0.3 for 30%) to be considered the same general route.
     * @return                   True if the overlap is below the threshold, indicating an alternative route.
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
}
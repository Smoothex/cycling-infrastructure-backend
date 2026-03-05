package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.RidePoint;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.PointList;
import com.graphhopper.util.details.PathDetail;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
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
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Value("${analysis.detour.threshold}")
    private double detourThreshold;

    public DetourAnalysisService(GraphHopperService graphHopperService,
                                 RideRepository rideRepository,
                                 StreetSegmentService streetSegmentService) {
        this.graphHopperService = graphHopperService;
        this.rideRepository = rideRepository;
        this.streetSegmentService = streetSegmentService;
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
                // 1. Reconstruct Shortest Path Geometry
                PointList ghPoints = shortestPath.getPoints();
                Coordinate[] coords = new Coordinate[ghPoints.size()];
                for (int i = 0; i < ghPoints.size(); i++) {
                    coords[i] = new Coordinate(ghPoints.getLon(i), ghPoints.getLat(i));
                }
                LineString shortestPathGeometry = geometryFactory.createLineString(coords);
                LineString actualTrajectory = ride.getTrajectory();

                double distanceThresholdDegrees = 0.0002;   // Roughly 20 meters in degrees

                Set<Integer> avoidedEdges = filterSpatiallyDistantEdges(
                        shortestEdges,
                        actualEdges,
                        actualTrajectory,
                        distanceThresholdDegrees
                );

                if (isAlternativeRoute(shortestEdges, avoidedEdges, 0.30)) {
                    log.info("Ride {} is an ALTERNATIVE ROUTE. Skipping edge registration to prevent noise.", ride.getId());
                    return markAs(ride, Status.PROCESSED);  // TODO: Consider a separate status for alternative routes
                }

                Set<Integer> chosenEdges = filterSpatiallyDistantEdges(
                        actualEdges,
                        shortestEdges,
                        shortestPathGeometry,
                        distanceThresholdDegrees
                );

                ride.setAvoidedEdgeIds(new ArrayList<>(avoidedEdges));
                ride.setChosenEdgeIds(new ArrayList<>(chosenEdges));

                streetSegmentService.registerAvoidedEdges(avoidedEdges, graphHopperService);

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
     * Filters a set of GraphHopper edges to find those that are physically distant from a reference path.
     * <p>
     * This solves the "parallel edge" problem in OSM/GraphHopper where a cyclist riding on a
     * segregated cycle path or in the opposite direction is assigned a different edge ID than the
     * main road. Instead of relying purely on ID differences, this method checks the actual
     * spatial distance between the edge and the reference geometry.
     *
     * @param sourceEdges        The set of GraphHopper edge IDs to be evaluated (e.g., the shortest path edges).
     * @param referenceEdges     The set of baseline edge IDs to skip. If an edge ID is in this set,
     *                           it is considered shared and immediately excluded from the results.
     * @param referenceGeometry  The JTS LineString representing the physical path to measure against
     *                           (e.g., the user's actual GPS trajectory).
     * @param distanceThreshold  The maximum allowed distance between a source edge and the reference geometry for
     *                           it to be considered "close enough". Unit in degrees.
     * @return                   A filtered Set of edge IDs from the sourceEdges that are
     *                           spatially divergent from the reference geometry.
     */
    private Set<Integer> filterSpatiallyDistantEdges(Set<Integer> sourceEdges,
                                                     Set<Integer> referenceEdges,
                                                     LineString referenceGeometry,
                                                     double distanceThreshold) {
        Set<Integer> filteredEdges = new HashSet<>();

        for (Integer edgeId : sourceEdges) {
            // Only evaluate edges that aren't already shared
            if (!referenceEdges.contains(edgeId)) {
                LineString edgeGeometry = graphHopperService.getEdgeGeometry(edgeId);

                if (edgeGeometry != null) {
                    // Check if the edge geometry is spatially distant from the reference geometry
                    if (referenceGeometry.distance(edgeGeometry) > distanceThreshold) {
                        filteredEdges.add(edgeId);
                    }
                } else {
                    filteredEdges.add(edgeId);  // fallback if geometry not available
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
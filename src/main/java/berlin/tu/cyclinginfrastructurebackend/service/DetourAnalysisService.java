package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.RidePoint;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import com.graphhopper.ResponsePath;
import com.graphhopper.util.details.PathDetail;
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
        // 1. Fetch a fresh, attached entity inside the active transaction
        Ride ride = rideRepository.findById(rideId).orElse(null);
        if (ride == null) {
            log.warn("Ride with ID {} not found during analysis.", rideId);
            return Status.ERROR;
        }

        try {
            // get RidePoints sorted by timestamp, filtering out any without location
            List<RidePoint> points = ride.getRidePoints().stream()
                    .filter(p -> p.getLocation() != null)
                    .sorted(Comparator.comparingLong(RidePoint::getTimestamp))
                    .toList();

            // need at least 2 valid points and some traversed edges to analyze
            if (points.size() < 2 || ride.getTraversedEdgeIds().isEmpty()) {
                return markAs(ride, Status.SKIPPED);
            }

            RidePoint start = points.getFirst();
            RidePoint end = points.getLast();

            // Calculate Theoretical Shortest Path
            ResponsePath shortestPath = graphHopperService.getShortestPath(
                    start.getLocation().getY(), start.getLocation().getX(),
                    end.getLocation().getY(), end.getLocation().getX()
            );

            if (shortestPath == null) {
                return markAs(ride, Status.SKIPPED);
            }

            // Extract edge IDs from GraphHopper response
            Set<Integer> shortestEdges = extractEdgeIds(shortestPath);
            Set<Integer> actualEdges = new HashSet<>(ride.getTraversedEdgeIds());

            // Distance Check
            double shortestPathDistance = shortestPath.getDistance();
            double actualDistance = ride.getActualDistance();

            ride.setShortestPathDistance(shortestPathDistance);
            ride.setActualDistance(actualDistance);

            boolean isDetour = actualDistance > shortestPathDistance * (1.0 + detourThreshold);
            ride.setIsDetour(isDetour);

            // If it's a detour, identify avoided and chosen edges
            if (isDetour) {
                Set<Integer> avoidedEdges = new HashSet<>(shortestEdges);
                avoidedEdges.removeAll(actualEdges);

                Set<Integer> chosenEdges = new HashSet<>(actualEdges);
                chosenEdges.removeAll(shortestEdges);

                ride.setAvoidedEdgeIds(new ArrayList<>(avoidedEdges));
                ride.setChosenEdgeIds(new ArrayList<>(chosenEdges));

                avoidedEdges.forEach(edgeId -> streetSegmentService.registerAvoidedEdge(edgeId, graphHopperService));

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
}
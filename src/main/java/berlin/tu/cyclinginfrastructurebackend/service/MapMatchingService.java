package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.RidePoint;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import com.graphhopper.matching.EdgeMatch;
import com.graphhopper.matching.MatchResult;
import com.graphhopper.matching.Observation;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class MapMatchingService {
    private static final Logger log = LoggerFactory.getLogger(MapMatchingService.class);

    private final GraphHopperService hopperService;
    private final StreetSegmentService segmentService;
    private final RideRepository rideRepository;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    public MapMatchingService(GraphHopperService hopperService,
                              StreetSegmentService segmentService,
                              RideRepository rideRepository) {
        this.hopperService = hopperService;
        this.segmentService = segmentService;
        this.rideRepository = rideRepository;
    }

    @Transactional
    public boolean processRide(Ride ride) {
        List<RidePoint> validPoints = filterAndSortPoints(ride);
        if (validPoints.size() < 2) return false;

        try {
            List<Observation> observations = validPoints.stream()
                    .map(p -> new Observation(new GHPoint(p.getLocation().getY(), p.getLocation().getX())))
                    .collect(Collectors.toList());

            MatchResult result = hopperService.match(observations);
            ride.setActualDistance(result.getMatchLength());
            updateRideTrajectory(ride, result);

            // Extract edge IDs
            List<EdgeIteratorState> edges = result.getEdgeMatches().stream()
                    .map(EdgeMatch::getEdgeState)
                    .collect(Collectors.toList());

            ride.setTraversedEdgeIds(edges.stream().map(EdgeIteratorState::getEdge).collect(Collectors.toList()));

            // Update segments (Sort to prevent deadlocks)
            edges.sort(Comparator.comparingLong(EdgeIteratorState::getEdge));
            for (EdgeIteratorState edge : edges) {
                segmentService.updateUsage(edge, hopperService);
            }

            ride.setStatus(Status.PENDING);
            rideRepository.save(ride);
            return true;
        } catch (Exception e) {
            log.error("Failed to process ride {}: {}", ride.getId(), e.getMessage());
            return false;
        }
    }

    private void updateRideTrajectory(Ride ride, MatchResult result) {
        List<Coordinate> allCoords = new ArrayList<>();
        List<EdgeMatch> matches = result.getEdgeMatches();

        for (int i = 0; i < matches.size(); i++) {
            PointList pl = matches.get(i).getEdgeState().fetchWayGeometry(FetchMode.ALL);
            for (int j = 0; j < pl.size(); j++) {
                // Skip the first point of subsequent edges to avoid duplicates
                if (i > 0 && j == 0) continue;
                allCoords.add(new Coordinate(pl.getLon(j), pl.getLat(j)));
            }
        }

        if (allCoords.size() >= 2) {
            ride.setTrajectory(geometryFactory.createLineString(allCoords.toArray(new Coordinate[0])));
        }
    }

    private List<RidePoint> filterAndSortPoints(Ride ride) {
        return ride.getRidePoints().stream()
                .filter(p -> p.getLocation() != null && p.getTimestamp() != null)
                .filter(p -> isValidCoordinate(p.getLocation().getY(), p.getLocation().getX()))
                .sorted(Comparator.comparingLong(RidePoint::getTimestamp))
                .collect(Collectors.toList());
    }

    private boolean isValidCoordinate(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
                && lat != 0.0 && lon != 0.0;
    }
}
package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.RidePoint;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
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
import java.util.stream.Collectors;

@Service
public class MapMatchingService {
    private static final Logger log = LoggerFactory.getLogger(MapMatchingService.class);

    private final GraphHopperService hopperService;
    private final StreetSegmentService segmentService;
    private final RideRepository rideRepository;
    private final double minimumOriginDestinationDistanceMeters;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    public MapMatchingService(GraphHopperService hopperService,
                              StreetSegmentService segmentService,
                              RideRepository rideRepository,
                              @Value("${analysis.minimum-origin-destination-distance-meters:500}")
                              double minimumOriginDestinationDistanceMeters) {
        this.hopperService = hopperService;
        this.segmentService = segmentService;
        this.rideRepository = rideRepository;
        this.minimumOriginDestinationDistanceMeters = minimumOriginDestinationDistanceMeters;
    }

    public RideProcessingResult processRide(Ride ride) {
        long totalStartedAt = System.nanoTime();
        long graphHopperNanos = 0;
        long timestampCalculationNanos = 0;
        long segmentUpdateNanos = 0;
        long ridePersistenceNanos = 0;
        try {
            List<RidePoint> validPoints = filterAndSortPoints(ride);
            if (validPoints.size() < 2) {
                return result(false, totalStartedAt, graphHopperNanos, timestampCalculationNanos,
                        segmentUpdateNanos, ridePersistenceNanos);
            }

            double originDestinationDistanceMeters = calculateOriginDestinationDistanceMeters(validPoints);
            if (originDestinationDistanceMeters < minimumOriginDestinationDistanceMeters) {
                log.debug(
                        "Skipping ride {}: origin-destination distance {} m is below the minimum of {} m",
                        ride.getId(), originDestinationDistanceMeters, minimumOriginDestinationDistanceMeters);
                ride.setStatus(Status.SKIPPED);
                long persistenceStartedAt = System.nanoTime();
                try {
                    rideRepository.save(ride);
                } finally {
                    ridePersistenceNanos = System.nanoTime() - persistenceStartedAt;
                }
                return result(true, totalStartedAt, graphHopperNanos, timestampCalculationNanos,
                        segmentUpdateNanos, ridePersistenceNanos);
            }

            List<Observation> observations = validPoints.stream()
                    .map(p -> new Observation(new GHPoint(p.getLocation().getY(), p.getLocation().getX())))
                    .collect(Collectors.toList());

            MatchResult matchResult;
            long graphHopperStartedAt = System.nanoTime();
            try {
                matchResult = hopperService.match(observations);
            } finally {
                graphHopperNanos = System.nanoTime() - graphHopperStartedAt;
            }
            ride.setActualDistance(matchResult.getMatchLength());
            updateRideTrajectory(ride, matchResult);

            // Extract edge IDs
            List<EdgeMatch> edgeMatches = matchResult.getEdgeMatches();
            List<EdgeIteratorState> edges = edgeMatches.stream()
                    .map(EdgeMatch::getEdgeState)
                    .collect(Collectors.toList());

            ride.setTraversedEdgeIds(edges.stream().map(EdgeIteratorState::getEdge).collect(Collectors.toList()));
            ride.setTraversedEdgeBearings(computeEdgeBearings(edgeMatches));
            long timestampStartedAt = System.nanoTime();
            try {
                ride.setTraversedEdgeTimestamps(computeEdgeTimestamps(edgeMatches, validPoints));
            } finally {
                timestampCalculationNanos = System.nanoTime() - timestampStartedAt;
            }

            long segmentUpdateStartedAt = System.nanoTime();
            try {
                segmentService.recordUsage(edges, hopperService);
            } finally {
                segmentUpdateNanos = System.nanoTime() - segmentUpdateStartedAt;
            }

            ride.setStatus(Status.PENDING);
            long persistenceStartedAt = System.nanoTime();
            try {
                rideRepository.save(ride);
            } finally {
                ridePersistenceNanos = System.nanoTime() - persistenceStartedAt;
            }
            return result(true, totalStartedAt, graphHopperNanos, timestampCalculationNanos,
                    segmentUpdateNanos, ridePersistenceNanos);
        } catch (Exception e) {
            log.error("Failed to process ride {}: {}", ride.getId(), e.getMessage());
            return result(false, totalStartedAt, graphHopperNanos, timestampCalculationNanos,
                    segmentUpdateNanos, ridePersistenceNanos);
        }
    }

    private RideProcessingResult result(boolean success,
                                        long totalStartedAt,
                                        long graphHopperNanos,
                                        long timestampCalculationNanos,
                                        long segmentUpdateNanos,
                                        long ridePersistenceNanos) {
        return new RideProcessingResult(
                success,
                System.nanoTime() - totalStartedAt,
                graphHopperNanos,
                timestampCalculationNanos,
                segmentUpdateNanos,
                ridePersistenceNanos
        );
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

    private double calculateOriginDestinationDistanceMeters(List<RidePoint> sortedPoints) {
        RidePoint origin = sortedPoints.getFirst();
        RidePoint destination = sortedPoints.getLast();
        return DistanceCalcEarth.DIST_EARTH.calcDist(
                origin.getLocation().getY(), origin.getLocation().getX(),
                destination.getLocation().getY(), destination.getLocation().getX());
    }

    /**
     * Computes compass bearings for each edge in the match result.
     * <p>
     * This method is called during map matching while the EdgeMatch objects are still available.
     * The EdgeIteratorState from EdgeMatch.getEdgeState() returns geometry in the direction of
     * traversal, so the bearing accurately reflects the rider's travel direction without needing
     * any reversal logic.
     *
     * @param edgeMatches the list of edge matches from the map matching result
     * @return a map from edge ID to bearing in degrees (0-360), preserving traversal order;
     *         edges with invalid geometry will have null bearings
     */
    private Map<Integer, Double> computeEdgeBearings(List<EdgeMatch> edgeMatches) {
        Map<Integer, Double> bearings = new LinkedHashMap<>();

        for (EdgeMatch match : edgeMatches) {
            EdgeIteratorState edgeState = match.getEdgeState();
            int edgeId = edgeState.getEdge();

            // Skip if we've already computed a bearing for this edge (can happen with loops)
            if (bearings.containsKey(edgeId)) {
                continue;
            }

            PointList geometry = edgeState.fetchWayGeometry(FetchMode.ALL);
            if (geometry == null || geometry.size() < 2) {
                bearings.put(edgeId, null);
                continue;
            }

            // Geometry is already in traversal direction, so compute bearing from start to end
            Double bearing = BearingCalculator.calculateBearing(geometry, 0, geometry.size() - 1);
            bearings.put(edgeId, bearing);
        }

        return bearings;
    }

    /**
     * Computes timestamps for each edge by finding the closest RidePoint to each edge's geometry.
     * Uses spatial distance to match GPS points to map-matched edges.
     *
     * @param edgeMatches the list of edge matches from map matching
     * @param ridePoints the original GPS points with timestamps
     * @return a map from edge ID to timestamp (milliseconds since epoch)
     */
    private Map<Integer, Long> computeEdgeTimestamps(List<EdgeMatch> edgeMatches,
                                                      List<RidePoint> ridePoints) {
        Map<Integer, Long> timestamps = new LinkedHashMap<>();
        DistanceCalcEarth distCalc = new DistanceCalcEarth();

        for (EdgeMatch match : edgeMatches) {
            EdgeIteratorState edgeState = match.getEdgeState();
            int edgeId = edgeState.getEdge();

            // Skip if already computed
            if (timestamps.containsKey(edgeId)) {
                continue;
            }

            // Get edge geometry
            PointList geometry = edgeState.fetchWayGeometry(FetchMode.ALL);
            if (geometry == null || geometry.isEmpty()) {
                timestamps.put(edgeId, null);
                continue;
            }

            // Use edge midpoint
            int midIdx = geometry.size() / 2;
            double edgeLat = geometry.getLat(midIdx);
            double edgeLon = geometry.getLon(midIdx);

            // Find closest RidePoint
            RidePoint closestPoint = null;
            double minDistance = Double.MAX_VALUE;

            for (RidePoint point : ridePoints) {
                if (point.getLocation() == null || point.getTimestamp() == null) {
                    continue;
                }

                double pointLat = point.getLocation().getY();
                double pointLon = point.getLocation().getX();
                double distance = distCalc.calcDist(edgeLat, edgeLon, pointLat, pointLon);

                if (distance < minDistance) {
                    minDistance = distance;
                    closestPoint = point;
                }
            }

            timestamps.put(edgeId, closestPoint != null ? closestPoint.getTimestamp() : null);
        }

        return timestamps;
    }
}

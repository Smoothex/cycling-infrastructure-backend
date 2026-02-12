package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.RidePoint;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.json.Statement;
import com.graphhopper.matching.EdgeMatch;
import com.graphhopper.matching.MapMatching;
import com.graphhopper.matching.MatchResult;
import com.graphhopper.matching.Observation;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.BBox;
import com.graphhopper.util.shapes.GHPoint;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class GraphHopperMapMatchingService {

    private static final Logger log = LoggerFactory.getLogger(GraphHopperMapMatchingService.class);
    private static final String PROFILE_NAME = "bike";

    private final StreetSegmentRepository streetSegmentRepository;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Value("${graphhopper.osm.file}")
    private String osmFile;

    @Value("${graphhopper.graph.location}")
    private String graphLocation;

    private GraphHopper hopper;

    private final ThreadLocal<MapMatching> mapMatchingThreadLocal = new ThreadLocal<>();

    public GraphHopperMapMatchingService(StreetSegmentRepository streetSegmentRepository) {
        this.streetSegmentRepository = streetSegmentRepository;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing GraphHopper with OSM file: {}", osmFile);

        if (!Files.exists(Path.of(osmFile))) {
            log.error("OSM file not found: {}. Download from https://download.geofabrik.de/europe/germany/berlin.html", osmFile);
            throw new IllegalStateException("OSM file not found: " + osmFile);
        }

        hopper = new GraphHopper();
        hopper.setOSMFile(osmFile);
        hopper.setGraphHopperLocation(graphLocation);

        // define custom bike profile
        hopper.setEncodedValuesString("road_class,road_access,max_speed,road_environment,surface");
        CustomModel customModel = new CustomModel();
        customModel.addToSpeed(Statement.If("true", Statement.Op.LIMIT, "20"));
        customModel.addToPriority(Statement.If("road_access == DESTINATION", Statement.Op.MULTIPLY, "0.1"));
        customModel.addToPriority(Statement.If("road_class == CYCLEWAY", Statement.Op.MULTIPLY, "1.5"));

        Profile bikeProfile = new Profile(PROFILE_NAME).setCustomModel(customModel);
        hopper.setProfiles(bikeProfile);

        // Import OSM data or load existing graph
        hopper.importOrLoad();

        log.info("GraphHopper initialized. Graph has {} nodes and {} edges.",
                hopper.getBaseGraph().getNodes(),
                hopper.getBaseGraph().getEdges());
    }

    @PreDestroy
    public void shutdown() {
        if (hopper != null) {
            hopper.close();
            log.info("GraphHopper shut down.");
        }
    }

    /**
     * Map-matches a ride to the road network.
     * <p>
     * Sets the snapped trajectory on the ride and updates street segment usage counts.
     *
     * @param ride the ride to map-match (must have valid RidePoints with locations)
     */
    public void mapMatch(Ride ride) {
        List<RidePoint> validPoints = filterAndSortPoints(ride);

        String rideIdentifier = ride.getOriginalFilename() != null
                ? ride.getOriginalFilename()
                : ride.getId().toString();

        if (validPoints.size() < 2) {
            log.warn("Ride {} has too few valid points for matching ({} points).",
                    rideIdentifier, validPoints.size());
            return;
        }

        try {
            // Convert to GraphHopper observations
            List<Observation> observations = new ArrayList<>();
            for (RidePoint p : validPoints) {
                observations.add(new Observation(new GHPoint(
                        p.getLocation().getY(),  // latitude
                        p.getLocation().getX()   // longitude
                )));
            }

            // Get or create thread-local MapMatching instance
            MapMatching mapMatching = getMapMatching();

            // Perform map matching
            MatchResult result = mapMatching.match(observations);

            // Extract and set snapped trajectory
            setSnappedTrajectory(ride, result);

            // Extract edge IDs and update segment usage
            processMatchedEdges(ride, result);

            log.debug("Ride {} matched: {} edges, {}m length",
                    rideIdentifier,
                    result.getEdgeMatches().size(),
                    result.getMatchLength());

        } catch (IllegalArgumentException e) {
            // Common: "Sequence is broken for submitted track" - points too far from roads
            log.warn("Map matching failed for ride {}: {}", rideIdentifier, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error matching ride {}", rideIdentifier, e);
        }
    }

    /**
     * Filters and sorts ride points for map matching.
     * Ensures points have valid locations and are sorted by timestamp.
     */
    private List<RidePoint> filterAndSortPoints(Ride ride) {
        return ride.getRidePoints().stream()
                .filter(p -> p.getLocation() != null && p.getTimestamp() != null)
                .filter(p -> isValidCoordinate(p.getLocation().getY(), p.getLocation().getX()))
                .sorted(Comparator.comparingLong(RidePoint::getTimestamp))
                .collect(Collectors.toList());
    }

    /**
     * Validates that coordinates are within reasonable bounds.
     */
    private boolean isValidCoordinate(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180
                && lat != 0.0 && lon != 0.0;
    }

    private MapMatching getMapMatching() {
        MapMatching mm = mapMatchingThreadLocal.get();
        if (mm == null) {
            // GraphHopper 11.0: Use static factory method
            mm = MapMatching.fromGraphHopper(hopper, new PMap().putObject("profile", PROFILE_NAME));
            mapMatchingThreadLocal.set(mm);
            log.debug("Created new MapMatching instance for thread {}", Thread.currentThread().getName());
        }
        return mm;
    }

    /**
     * Extracts the snapped geometry from match result and sets it on the ride.
     */
    private void setSnappedTrajectory(Ride ride, MatchResult result) {
        List<Coordinate> coords = new ArrayList<>();

        for (EdgeMatch edgeMatch : result.getEdgeMatches()) {
            EdgeIteratorState edge = edgeMatch.getEdgeState();
            PointList points = edge.fetchWayGeometry(FetchMode.ALL);
            for (int i = 0; i < points.size(); i++) {
                coords.add(new Coordinate(points.getLon(i), points.getLat(i)));
            }
        }

        // Remove duplicate consecutive coordinates
        List<Coordinate> dedupedCoords = new ArrayList<>();
        for (Coordinate coord : coords) {
            if (dedupedCoords.isEmpty() || !coord.equals2D(dedupedCoords.getLast())) {
                dedupedCoords.add(coord);
            }
        }

        if (dedupedCoords.size() >= 2) {
            LineString trajectory = geometryFactory.createLineString(
                    dedupedCoords.toArray(new Coordinate[0]));
            ride.setTrajectory(trajectory);
        }
    }

    /**
     * Processes matched edges: extracts edge IDs, updates segment usage counts,
     * and stores edge geometry.
     */
    private void processMatchedEdges(Ride ride, MatchResult result) {
        List<Long> edgeIds = new ArrayList<>();

        for (EdgeMatch edgeMatch : result.getEdgeMatches()) {
            EdgeIteratorState edge = edgeMatch.getEdgeState();
            long edgeId = edge.getEdge();

            edgeIds.add(edgeId);
            updateSegmentUsage(edge);
        }

        // Store traversed edges on the ride for later analysis
        ride.setTraversedEdgeIds(edgeIds);
    }

    /**
     * Creates or updates a street segment with usage count and geometry.
     */
    private synchronized void updateSegmentUsage(EdgeIteratorState edge) {
        long edgeId = edge.getEdge();

        StreetSegment segment = streetSegmentRepository.findById(edgeId).orElse(null);

        if (segment == null) {
            segment = new StreetSegment();
            segment.setId(edgeId);

            String streetName = edge.getName();
            if (streetName == null || streetName.isBlank()) {
                streetName = findNearestStreetName(edge);
            }
            segment.setStreetName(streetName != null && !streetName.isBlank() ? streetName : "Unknown");

            // Store the actual road geometry
            PointList points = edge.fetchWayGeometry(FetchMode.ALL);
            if (points.size() >= 2) {
                Coordinate[] coords = new Coordinate[points.size()];
                for (int i = 0; i < points.size(); i++) {
                    coords[i] = new Coordinate(points.getLon(i), points.getLat(i));
                }
                segment.setGeometry(geometryFactory.createLineString(coords));
            }
        }

        segment.incrementUsage();
        streetSegmentRepository.save(segment);
    }

    private String findNearestStreetName(EdgeIteratorState sourceEdge) {
        PointList points = sourceEdge.fetchWayGeometry(FetchMode.ALL);
        if (points.isEmpty()) return null;

        // Use the middle point of the geometry for the search
        int midIndex = points.size() / 2;
        double lat = points.getLat(midIndex);
        double lon = points.getLon(midIndex);

        // Search within ~20 meters (approx 0.0002 degrees)
        double radius = 0.0002;
        BBox bbox = new BBox(lon - radius, lon + radius, lat - radius, lat + radius);

        final String[] bestName = {null};
        final double[] minDistance = {Double.MAX_VALUE};
        final DistanceCalcEarth distCalc = new DistanceCalcEarth();

        hopper.getLocationIndex().query(bbox, edgeId -> {
            if (edgeId == sourceEdge.getEdge()) return; // Skip self

            EdgeIteratorState candidate = hopper.getBaseGraph().getEdgeIteratorState(edgeId, Integer.MIN_VALUE);
            if (candidate == null) return;

            String name = candidate.getName();
            if (name == null || name.isBlank()) return;

            // Calculate min distance from source midpoint to candidate geometry
            PointList candidatePoints = candidate.fetchWayGeometry(FetchMode.ALL);
            double currentMinDist = Double.MAX_VALUE;

            for (int i = 0; i < candidatePoints.size() - 1; i++) {
                double dist = distCalc.calcDist(
                        candidatePoints.getLat(i), candidatePoints.getLon(i),
                        candidatePoints.getLat(i + 1), candidatePoints.getLon(i + 1)
                );
                if (dist < currentMinDist) currentMinDist = dist;
            }

            if (currentMinDist < minDistance[0]) {
                minDistance[0] = currentMinDist;
                bestName[0] = name;
            }
        });

        return bestName[0];
    }
}

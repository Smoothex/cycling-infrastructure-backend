package berlin.tu.cyclinginfrastructurebackend.service;

import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.json.Statement;
import com.graphhopper.matching.MapMatching;
import com.graphhopper.matching.MatchResult;
import com.graphhopper.matching.Observation;
import com.graphhopper.reader.dem.SRTMProvider;
import com.graphhopper.util.*;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.ResponsePath;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
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
import java.util.List;

@Service
public class GraphHopperService {
    private static final Logger log = LoggerFactory.getLogger(GraphHopperService.class);

    @Value("${graphhopper.osm.file}")
    private String osmFile;

    @Value("${graphhopper.graph.location}")
    private String graphLocation;

    @Value("${graphhopper.elevation.cache_dir}")
    private String elevationCacheDir;

    @Getter
    private GraphHopper hopper;
    private final ThreadLocal<MapMatching> mapMatchingThreadLocal = new ThreadLocal<>();
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);
    private static final String PROFILE_BIKE_CUSTOM = "bike_custom";
    private static final String PROFILE_BIKE_SHORTEST = "bike_shortest";

    @PostConstruct
    public void init() {
        if (!Files.exists(Path.of(osmFile))) {
            throw new IllegalStateException("OSM file not found: " + osmFile);
        }

        hopper = new GraphHopper();
        hopper.setOSMFile(osmFile);
        hopper.setGraphHopperLocation(graphLocation);
        hopper.setElevation(true);
        hopper.setElevationProvider(new SRTMProvider(elevationCacheDir));

        hopper.setEncodedValuesString("road_class,road_access,max_speed,road_environment,surface");

        CustomModel customModel = new CustomModel();
        customModel.addToSpeed(Statement.If("true", Statement.Op.LIMIT, "20"));
        customModel.addToPriority(Statement.If("road_class == CYCLEWAY", Statement.Op.MULTIPLY, "1.5"));
        Profile bikeCustomProfile = new Profile(PROFILE_BIKE_CUSTOM).setCustomModel(customModel);

        CustomModel shortestModel = new CustomModel();
        shortestModel.addToSpeed(Statement.If("true", Statement.Op.LIMIT, "20"));
        shortestModel.setDistanceInfluence(100.0);  // prioritize shortest path
        Profile bikeShortestProfile = new Profile(PROFILE_BIKE_SHORTEST).setCustomModel(shortestModel);

        hopper.setProfiles(bikeCustomProfile, bikeShortestProfile);
        hopper.importOrLoad();
    }

    public MatchResult match(List<Observation> observations) {
        MapMatching mm = mapMatchingThreadLocal.get();
        if (mm == null) {
            mm = MapMatching.fromGraphHopper(hopper, new PMap().putObject("profile", PROFILE_BIKE_CUSTOM));
            mapMatchingThreadLocal.set(mm);
        }
        return mm.match(observations);
    }

    public ResponsePath getShortestPath(double fromLat, double fromLon, double toLat, double toLon) {
        GHRequest req = new GHRequest(fromLat, fromLon, toLat, toLon)
                .setProfile(PROFILE_BIKE_SHORTEST)
                .setPathDetails(List.of("edge_id")); // Explicitly request edge IDs

        GHResponse rsp = hopper.route(req);
        if (rsp.hasErrors() || rsp.getAll().isEmpty()) {
            log.warn("GraphHopper routing failed for coords: {}/{} to {}/{}", fromLat, fromLon, toLat, toLon);
            return null;
        }
        return rsp.getBest();
    }

    /** Computes average gradient (%) for an edge. Positive = uphill, negative = downhill. */
    public Double getGradientPercent(int edgeId) {
        EdgeIteratorState edge = hopper.getBaseGraph().getEdgeIteratorState(edgeId, Integer.MIN_VALUE);
        PointList points = edge.fetchWayGeometry(FetchMode.ALL);
        if (!points.is3D()) {
            log.warn("Edge {} has no elevation data (is3D=false)", edgeId);
            return null;
        }

        double startElevation = points.getEle(0);
        double endElevation = points.getEle(points.size() - 1);
        double distance = edge.getDistance();

        if (distance < 1.0) return 0.0;

        return ((endElevation - startElevation) / distance) * 100.0;
    }


    public LineString getEdgeGeometry(int edgeId) {
        EdgeIteratorState edge = hopper.getBaseGraph().getEdgeIteratorState(edgeId, Integer.MIN_VALUE);

        if (edge == null) return null;

        PointList points = edge.fetchWayGeometry(FetchMode.ALL);
        if (points.isEmpty()) return null;

        Coordinate[] coords = new Coordinate[points.size()];
        for (int i = 0; i < points.size(); i++) {
            coords[i] = new Coordinate(points.getLon(i), points.getLat(i));
        }

        if (coords.length < 2) return null;

        return geometryFactory.createLineString(coords);
    }

    @PreDestroy
    public void shutdown() {
        if (hopper != null) hopper.close();
    }
}
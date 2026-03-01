package berlin.tu.cyclinginfrastructurebackend.service;

import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.json.Statement;
import com.graphhopper.matching.MapMatching;
import com.graphhopper.matching.MatchResult;
import com.graphhopper.matching.Observation;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.PMap;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.ResponsePath;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
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

    @Getter
    private GraphHopper hopper;
    private final ThreadLocal<MapMatching> mapMatchingThreadLocal = new ThreadLocal<>();

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

    @PreDestroy
    public void shutdown() {
        if (hopper != null) hopper.close();
    }
}
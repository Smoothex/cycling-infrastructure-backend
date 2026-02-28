package berlin.tu.cyclinginfrastructurebackend.service;

import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.json.Statement;
import com.graphhopper.matching.MapMatching;
import com.graphhopper.matching.MatchResult;
import com.graphhopper.matching.Observation;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.PMap;
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
    private static final String PROFILE_NAME = "bike";

    @Value("${graphhopper.osm.file}")
    private String osmFile;

    @Value("${graphhopper.graph.location}")
    private String graphLocation;

    @Getter
    private GraphHopper hopper;
    private final ThreadLocal<MapMatching> mapMatchingThreadLocal = new ThreadLocal<>();

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

        Profile bikeProfile = new Profile(PROFILE_NAME).setCustomModel(customModel);
        hopper.setProfiles(bikeProfile);
        hopper.importOrLoad();
    }

    public MatchResult match(List<Observation> observations) {
        MapMatching mm = mapMatchingThreadLocal.get();
        if (mm == null) {
            mm = MapMatching.fromGraphHopper(hopper, new PMap().putObject("profile", PROFILE_NAME));
            mapMatchingThreadLocal.set(mm);
        }
        return mm.match(observations);
    }

    @PreDestroy
    public void shutdown() {
        if (hopper != null) hopper.close();
    }
}
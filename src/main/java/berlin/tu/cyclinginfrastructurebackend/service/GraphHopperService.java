package berlin.tu.cyclinginfrastructurebackend.service;

import com.graphhopper.GraphHopper;
import com.graphhopper.config.CHProfile;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

@Service
public class GraphHopperService {
    private static final Logger log = LoggerFactory.getLogger(GraphHopperService.class);
    static final String PROFILE_BIKE_MATCH_NEUTRAL = "bike_match_neutral";
    static final String PROFILE_BIKE_SHORTEST = "bike_shortest";
    static final double PROFILE_SPEED_KMH = 20.0;
    static final double PROFILE_DISTANCE_INFLUENCE_SECONDS_PER_KM = 0.0;
    static final String ENCODED_VALUES = String.join(",",
            "bike_access|block_private=true",
            "roundabout",
            "road_class",
            "road_access",
            "max_speed",
            "road_environment",
            "surface");

    @Value("${graphhopper.osm.file}")
    private String osmFile;

    @Value("${graphhopper.osm.download-url}")
    private String osmDownloadUrl;

    @Value("${graphhopper.graph.location}")
    private String graphLocation;

    @Value("${graphhopper.elevation.cache_dir}")
    private String elevationCacheDir;

    @Getter
    private GraphHopper hopper;
    private final ThreadLocal<MapMatching> mapMatchingThreadLocal = new ThreadLocal<>();

    @PostConstruct
    public void init() {
        ensureOsmFile();

        hopper = new GraphHopper();
        hopper.setOSMFile(osmFile);
        hopper.setGraphHopperLocation(graphLocation);
        hopper.setElevation(true);
        hopper.setElevationProvider(new SRTMProvider(elevationCacheDir));

        hopper.setEncodedValuesString(ENCODED_VALUES);

        Profile bikeMatchProfile = new Profile(PROFILE_BIKE_MATCH_NEUTRAL)
                .setCustomModel(createNeutralBicycleModel());
        Profile bikeShortestProfile = new Profile(PROFILE_BIKE_SHORTEST)
                .setCustomModel(createNeutralBicycleModel());

        hopper.setProfiles(bikeMatchProfile, bikeShortestProfile);

        // set contraction hierarchy
        hopper.getCHPreparationHandler().setCHProfiles(new CHProfile(PROFILE_BIKE_SHORTEST));

        hopper.importOrLoad();
    }

    /**
     * Downloads the OSM extract if it is not present. The download goes to a
     * .part file first and is moved into place only on success, so an aborted
     * download is never mistaken for a complete file on the next startup.
     */
    private void ensureOsmFile() {
        Path target = Path.of(osmFile);
        if (Files.exists(target)) {
            return;
        }

        log.info("OSM file not found at {}, downloading from {} (several GB, this can take a while)",
                osmFile, osmDownloadUrl);
        Path partFile = target.resolveSibling(target.getFileName() + ".part");
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.deleteIfExists(partFile);

            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(osmDownloadUrl)).GET().build();
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(partFile));
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "OSM download failed with HTTP " + response.statusCode() + " from " + osmDownloadUrl);
            }

            Files.move(partFile, target, StandardCopyOption.ATOMIC_MOVE);
            log.info("OSM download complete: {} ({} MB)", osmFile, Files.size(target) / (1024 * 1024));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to download OSM file from " + osmDownloadUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OSM download interrupted", e);
        } finally {
            try {
                Files.deleteIfExists(partFile);
            } catch (IOException ignored) {
                // best-effort cleanup of the partial download
            }
        }
    }

    public MatchResult match(List<Observation> observations) {
        MapMatching mm = mapMatchingThreadLocal.get();
        if (mm == null) {
            mm = MapMatching.fromGraphHopper(
                    hopper,
                    new PMap().putObject("profile", PROFILE_BIKE_MATCH_NEUTRAL));
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

    /**
     * Creates a bicycle-access-aware model whose finite edge weights are strictly proportional
     * to physical distance. Infrastructure attributes are deliberately excluded so neither map
     * matching nor shortest-path routing assumes the preferences measured by the analysis.
     */
    static CustomModel createNeutralBicycleModel() {
        CustomModel model = new CustomModel();
        model.addToSpeed(Statement.If(
                "true",
                Statement.Op.LIMIT,
                Double.toString(PROFILE_SPEED_KMH)));
        model.addToPriority(Statement.If(
                "!bike_access",
                Statement.Op.MULTIPLY,
                "0"));
        model.setDistanceInfluence(PROFILE_DISTANCE_INFLUENCE_SECONDS_PER_KM);
        return model;
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

    @PreDestroy
    public void shutdown() {
        if (hopper != null) hopper.close();
    }
}

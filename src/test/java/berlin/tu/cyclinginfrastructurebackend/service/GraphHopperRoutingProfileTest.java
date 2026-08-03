package berlin.tu.cyclinginfrastructurebackend.service;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.ResponsePath;
import com.graphhopper.config.CHProfile;
import com.graphhopper.config.Profile;
import com.graphhopper.json.Statement;
import com.graphhopper.util.CustomModel;
import com.graphhopper.util.Parameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class GraphHopperRoutingProfileTest {

    @TempDir
    Path tempDirectory;

    private GraphHopper hopper;

    @BeforeEach
    void importTestNetwork() throws URISyntaxException {
        Path osmFile = Path.of(Objects.requireNonNull(
                getClass().getResource("/graphhopper/routing-profiles.osm.xml")).toURI());

        hopper = new GraphHopper();
        hopper.setOSMFile(osmFile.toString());
        hopper.setGraphHopperLocation(tempDirectory.resolve("graph-cache").toString());
        hopper.setMinNetworkSize(0);
        hopper.setEncodedValuesString(GraphHopperService.ENCODED_VALUES);
        hopper.setProfiles(
                new Profile(GraphHopperService.PROFILE_BIKE_MATCH_NEUTRAL)
                        .setCustomModel(GraphHopperService.createNeutralBicycleModel()),
                new Profile(GraphHopperService.PROFILE_BIKE_SHORTEST)
                        .setCustomModel(GraphHopperService.createNeutralBicycleModel()));
        hopper.getCHPreparationHandler().setCHProfiles(
                new CHProfile(GraphHopperService.PROFILE_BIKE_SHORTEST));
        hopper.importOrLoad();
    }

    @AfterEach
    void closeGraph() {
        if (hopper != null) {
            hopper.close();
        }
    }

    @Test
    void neutralModelUsesConstantSpeedAndBicycleAccessOnly() {
        CustomModel model = GraphHopperService.createNeutralBicycleModel();

        assertThat(model.getDistanceInfluence()).isZero();
        assertThat(model.getTurnPenalty()).isEmpty();
        assertThat(model.getSpeed()).containsExactly(Statement.If(
                "true", Statement.Op.LIMIT, "20.0"));
        assertThat(model.getPriority()).containsExactly(Statement.If(
                "!bike_access", Statement.Op.MULTIPLY, "0"));
    }

    @Test
    void shortestProfileRejectsBicycleProhibitedAndPrivateShortcuts() {
        assertThat(route(
                GraphHopperService.PROFILE_BIKE_SHORTEST,
                52.0000, 13.0000, 52.0000, 13.0020).getDistance())
                .isBetween(240.0, 280.0);
        assertThat(route(
                GraphHopperService.PROFILE_BIKE_SHORTEST,
                52.0200, 13.0000, 52.0200, 13.0020).getDistance())
                .isBetween(240.0, 280.0);
    }

    @Test
    void shortestProfileObeysDirectionalBicycleAccess() {
        double forwardDistance = route(
                GraphHopperService.PROFILE_BIKE_SHORTEST,
                52.0300, 13.0000, 52.0300, 13.0020).getDistance();
        double reverseDistance = route(
                GraphHopperService.PROFILE_BIKE_SHORTEST,
                52.0300, 13.0020, 52.0300, 13.0000).getDistance();

        assertThat(forwardDistance).isBetween(130.0, 145.0);
        assertThat(reverseDistance).isBetween(240.0, 280.0);
    }

    @Test
    void bothProfilesChooseTheShorterRoadWithoutCyclewayPreference() {
        assertThat(route(
                GraphHopperService.PROFILE_BIKE_MATCH_NEUTRAL,
                52.0100, 13.0000, 52.0100, 13.0020).getDistance())
                .isBetween(130.0, 145.0);
        assertThat(route(
                GraphHopperService.PROFILE_BIKE_SHORTEST,
                52.0100, 13.0000, 52.0100, 13.0020).getDistance())
                .isBetween(130.0, 145.0);
    }

    private ResponsePath route(
            String profile,
            double fromLatitude,
            double fromLongitude,
            double toLatitude,
            double toLongitude) {
        GHRequest request = new GHRequest(
                fromLatitude, fromLongitude, toLatitude, toLongitude).setProfile(profile);
        if (GraphHopperService.PROFILE_BIKE_MATCH_NEUTRAL.equals(profile)) {
            request.putHint(Parameters.CH.DISABLE, true);
        }
        GHResponse response = hopper.route(request);

        assertThat(response.getErrors()).isEmpty();
        return response.getBest();
    }
}

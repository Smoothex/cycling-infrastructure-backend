package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.SimRa;

import berlin.tu.cyclinginfrastructurebackend.service.ParsedRide;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SimRaFileParserTest {

    private final SimRaFileParser parser = new SimRaFileParser();

    @Test
    void parsesTransientTraceAndKeepsRideLevelAggregatesWithUnusedSensorColumns() throws Exception {
        ParsedRide parsed = parse("""
                ======
                lat,lon,X,Y,Z,timeStamp,acc,a,b,c,obsClosePassEvent
                52.50,13.40,1,2,3,3000,9,4,5,6,true
                52.51,13.41,1,2,3,1000,,4,5,6,false
                52.52,13.42,1,2,3,2000,3,4,5,6,false
                52.53,13.43,1,2,3,4000,6,4,5,6,false
                """);

        assertThat(parsed.trace()).hasSize(4);
        assertThat(parsed.trace().getFirst().location().getX()).isEqualTo(13.40);
        assertThat(parsed.trace().getFirst().timestamp()).isEqualTo(3_000L);
        assertThat(parsed.ride().getGpsPointCount()).isEqualTo(4L);
        assertThat(parsed.ride().getMedianGpsAccuracy()).isEqualTo(6.0);
        assertThat(parsed.ride().getStartTime()).isEqualTo(3_000L);
        assertThat(parsed.ride().getEndTime()).isEqualTo(4_000L);
        assertThat(parsed.ride().getTrajectory().getNumPoints()).isEqualTo(4);
    }

    @Test
    void usesAverageOfTwoMiddleNonNullAccuraciesForEvenInput() throws Exception {
        ParsedRide parsed = parse("""
                ======
                lat,lon,timeStamp,acc
                52.50,13.40,1000,1
                52.51,13.41,2000,9
                52.52,13.42,3000,
                52.53,13.43,4000,5
                52.54,13.44,5000,3
                """);

        assertThat(parsed.ride().getGpsPointCount()).isEqualTo(5L);
        assertThat(parsed.ride().getMedianGpsAccuracy()).isEqualTo(4.0);
    }

    @Test
    void leavesMedianNullWhenEveryAccuracyIsNull() throws Exception {
        ParsedRide parsed = parse("""
                ======
                lat,lon,timeStamp,acc
                52.50,13.40,1000,
                52.51,13.41,2000,
                """);

        assertThat(parsed.ride().getGpsPointCount()).isEqualTo(2L);
        assertThat(parsed.ride().getMedianGpsAccuracy()).isNull();
    }

    private ParsedRide parse(String csv) throws Exception {
        return parser.parse(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                "VM_test.csv");
    }
}

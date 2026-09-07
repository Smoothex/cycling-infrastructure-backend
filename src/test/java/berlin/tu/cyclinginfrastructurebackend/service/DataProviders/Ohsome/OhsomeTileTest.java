package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OhsomeTileTest {
    @ParameterizedTest
    @CsvSource({"134,525,13.39,52.49,13.51,52.61", "99,535,9.89,53.49,10.01,53.61",
            "115,481,11.49,48.09,11.61,48.21"})
    void germanCitiesHaveSmallBufferedBounds(int x, int y, double west, double south, double east, double north) {
        assertThat(new OhsomeTile(x, y).bounds(new OhsomeV2Properties()))
                .containsExactly(west, south, east, north);
    }

    @Test
    void completeWayAcrossTileBoundaryCanMatchSegmentInsideTile() {
        var factory = new GeometryFactory();
        var segment = factory.createLineString(new Coordinate[]{new Coordinate(13.45, 52.59999),
                new Coordinate(13.451, 52.59999)});
        // The way is just outside the unbuffered cell and extends beyond both cell edges.
        var way = factory.createLineString(new Coordinate[]{new Coordinate(13.39, 52.60005),
                new Coordinate(13.51, 52.60005)});
        var snapshot = new OhsomeSnapshot(List.of(new OhsomeFeature(1, way, Map.of())));
        assertThat(snapshot.match(segment, null).outcome()).isEqualTo(OhsomeFeatureMatch.Outcome.MATCHED);
    }

    @Test
    void rejectsInvalidGridBufferAndClipping() {
        var properties = new OhsomeV2Properties();
        for (double size : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            properties.setGridSizeDegrees(size);
            assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        }
        properties.setGridSizeDegrees(0.1);
        properties.setBufferDegrees(-1);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
        properties.setBufferDegrees(0.01);
        properties.setClip(true);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalArgumentException.class);
    }
}

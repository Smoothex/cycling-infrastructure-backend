package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.GeoJsonFeatureCollectionDto;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GeoJsonMapperTest {

    private final GeoJsonMapper mapper = new GeoJsonMapper();
    private final GeometryFactory geometryFactory = new GeometryFactory();

    @Test
    void mapsSegmentsToFeatureCollection() {
        StreetSegment segment = new StreetSegment();
        segment.setId(42L);
        segment.setStreetName("Test Street");
        segment.setUsageCount(10);
        segment.setAvoidanceCount(5);
        segment.setPreferenceCount(2);
        segment.setAvoidanceRatio(0.33);
        segment.setPreferenceRatio(0.17);
        segment.setGradientPercent(1.5);
        segment.setGeometry(geometryFactory.createLineString(new Coordinate[]{
                new Coordinate(13.1, 52.1),
                new Coordinate(13.2, 52.2)
        }));

        GeoJsonFeatureCollectionDto collection = mapper.toSegmentFeatureCollection(List.of(segment), Map.of());

        assertThat(collection.type()).isEqualTo("FeatureCollection");
        assertThat(collection.features()).hasSize(1);
        assertThat(collection.features().getFirst().geometry().type()).isEqualTo("LineString");
        assertThat(collection.features().getFirst().properties())
                .containsEntry("id", 42L)
                .containsEntry("totalObservationCount", 17);
    }
}

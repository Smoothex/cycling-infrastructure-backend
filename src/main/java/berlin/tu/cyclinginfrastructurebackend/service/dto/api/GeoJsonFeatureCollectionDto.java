package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

import java.util.List;
import java.util.Map;

public record GeoJsonFeatureCollectionDto(
        String type,
        List<FeatureDto> features
) {

    public record FeatureDto(
            String type,
            LineStringGeometryDto geometry,
            Map<String, Object> properties
    ) {
    }

    public record LineStringGeometryDto(
            String type,
            List<List<Double>> coordinates
    ) {
    }
}

package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

import java.util.List;

/** Complete OSM street geometry used to highlight an analytics corridor on the map. */
public record CorridorGeometryDto(
        String streetName,
        List<Long> segmentIds,
        MultiLineStringGeometry geometry
) {
    public record MultiLineStringGeometry(
            String type,
            List<List<List<Double>>> coordinates
    ) {
        public MultiLineStringGeometry(List<List<List<Double>>> coordinates) {
            this("MultiLineString", coordinates);
        }
    }
}

package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

import berlin.tu.cyclinginfrastructurebackend.domain.RoadClosure;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ExternalFactorType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RoadClosureSeverity;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * @param lon   label-point longitude (falls back to the first line coordinate)
 * @param lat   label-point latitude
 * @param lines affected road stretches as MultiLineString-style coordinate arrays
 *              ([line][vertex][lon, lat]); may be empty for point-only entries
 */
public record RoadClosureDto(
        UUID id,
        ExternalFactorType factorType,
        RoadClosureSeverity severity,
        String direction,
        String street,
        String section,
        String content,
        Long validFrom,
        Long validTo,
        double lon,
        double lat,
        List<List<List<Double>>> lines
) {

    public static RoadClosureDto from(RoadClosure closure) {
        Point labelPoint = null;
        List<List<List<Double>>> lines = new ArrayList<>();

        Geometry geometry = closure.getGeometry();
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            Geometry component = geometry.getGeometryN(i);
            if (component instanceof Point point && labelPoint == null) {
                labelPoint = point;
            } else if (component instanceof LineString line) {
                lines.add(Arrays.stream(line.getCoordinates())
                        .map(coordinate -> List.of(coordinate.getX(), coordinate.getY()))
                        .toList());
            }
        }

        Coordinate label = labelPoint != null
                ? labelPoint.getCoordinate()
                : geometry.getCoordinate();

        return new RoadClosureDto(
                closure.getId(),
                closure.getFactorType(),
                closure.getSeverity(),
                closure.getDirection(),
                closure.getStreet(),
                closure.getSection(),
                closure.getContent(),
                closure.getValidFrom(),
                closure.getValidTo(),
                label.getX(),
                label.getY(),
                lines
        );
    }
}

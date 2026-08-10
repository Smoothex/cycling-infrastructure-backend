package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/** Projects WGS84 geometries into ETRS89 / UTM zone 33N so all thresholds are in metres. */
final class OhsomeCoordinateTransformer {

    private static final GeometryFactory PROJECTED_FACTORY =
            new GeometryFactory(new PrecisionModel(), 25833);

    private final CoordinateTransform transform;

    OhsomeCoordinateTransformer() {
        CRSFactory crsFactory = new CRSFactory();
        CoordinateReferenceSystem source = crsFactory.createFromName("EPSG:4326");
        CoordinateReferenceSystem target = crsFactory.createFromName("EPSG:25833");
        if (source == null || target == null) {
            throw new IllegalStateException("Proj4j could not load EPSG:4326 or EPSG:25833");
        }
        transform = new CoordinateTransformFactory().createTransform(source, target);
    }

    LineString toMeters(LineString lineString) {
        Coordinate[] sourceCoordinates = lineString.getCoordinates();
        Coordinate[] targetCoordinates = new Coordinate[sourceCoordinates.length];
        ProjCoordinate source = new ProjCoordinate();
        ProjCoordinate target = new ProjCoordinate();

        for (int index = 0; index < sourceCoordinates.length; index++) {
            Coordinate coordinate = sourceCoordinates[index];
            source.x = coordinate.x;
            source.y = coordinate.y;
            transform.transform(source, target);
            if (!Double.isFinite(target.x) || !Double.isFinite(target.y)) {
                throw new IllegalArgumentException("Geometry contains a coordinate outside the supported projection");
            }
            targetCoordinates[index] = new Coordinate(target.x, target.y);
        }

        return PROJECTED_FACTORY.createLineString(targetCoordinates);
    }
}

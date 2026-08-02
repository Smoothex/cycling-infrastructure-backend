package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.service.dto.api.CorridorGeometryDto;
import com.graphhopper.GraphHopper;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.BBox;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class CorridorGeometryService {
    private static final double CORRIDOR_BUFFER_METERS = 75.0;
    private static final double METERS_PER_LATITUDE_DEGREE = 111_320.0;
    private static final double MAX_BOUNDS_SPAN_DEGREES = 0.25;

    private final GraphHopperService graphHopperService;
    private final GeometryFactory geometryFactory = new GeometryFactory();

    public CorridorGeometryService(GraphHopperService graphHopperService) {
        this.graphHopperService = graphHopperService;
    }

    public CorridorGeometryDto getCorridorGeometry(String streetName,
                                                    double minLon,
                                                    double minLat,
                                                    double maxLon,
                                                    double maxLat) {
        validateRequest(streetName, minLon, minLat, maxLon, maxLat);
        String displayName = streetName.trim();
        String normalizedName = normalizeName(displayName);
        BBox queryBounds = bufferedBounds(minLon, minLat, maxLon, maxLat);
        Geometry queryArea = geometryFactory.toGeometry(new Envelope(
                queryBounds.minLon, queryBounds.maxLon, queryBounds.minLat, queryBounds.maxLat));

        GraphHopper hopper = graphHopperService.getHopper();
        BaseGraph graph = hopper.getBaseGraph();
        Set<Integer> candidateIds = new HashSet<>();
        hopper.getLocationIndex().query(queryBounds, candidateIds::add);

        List<Long> segmentIds = new ArrayList<>();
        List<List<List<Double>>> coordinates = new ArrayList<>();
        candidateIds.stream().sorted(Comparator.naturalOrder()).forEach(edgeId -> {
            EdgeIteratorState edge = graph.getEdgeIteratorState(edgeId, Integer.MIN_VALUE);
            if (edge == null || !normalizeName(edge.getName()).equals(normalizedName)) {
                return;
            }

            PointList points = edge.fetchWayGeometry(FetchMode.ALL);
            if (points.size() < 2 || !points.toLineString(false).intersects(queryArea)) {
                return;
            }

            List<List<Double>> line = new ArrayList<>(points.size());
            for (int index = 0; index < points.size(); index++) {
                line.add(List.of(points.getLon(index), points.getLat(index)));
            }
            segmentIds.add(edgeId.longValue());
            coordinates.add(line);
        });

        return new CorridorGeometryDto(
                displayName,
                segmentIds,
                new CorridorGeometryDto.MultiLineStringGeometry(coordinates));
    }

    private void validateRequest(String streetName,
                                 double minLon,
                                 double minLat,
                                 double maxLon,
                                 double maxLat) {
        if (streetName == null || streetName.isBlank()) {
            throw badRequest("streetName must not be blank");
        }
        if (!Double.isFinite(minLon) || !Double.isFinite(minLat)
                || !Double.isFinite(maxLon) || !Double.isFinite(maxLat)) {
            throw badRequest("corridor bounds must be finite");
        }
        if (minLon < -180 || maxLon > 180 || minLat < -90 || maxLat > 90
                || minLon > maxLon || minLat > maxLat) {
            throw badRequest("corridor bounds are invalid");
        }
        if (maxLon - minLon > MAX_BOUNDS_SPAN_DEGREES
                || maxLat - minLat > MAX_BOUNDS_SPAN_DEGREES) {
            throw badRequest("corridor bounds are too large");
        }
    }

    private BBox bufferedBounds(double minLon, double minLat, double maxLon, double maxLat) {
        double latitudeBuffer = CORRIDOR_BUFFER_METERS / METERS_PER_LATITUDE_DEGREE;
        double midpointLatitude = (minLat + maxLat) / 2.0;
        double longitudeScale = Math.max(0.01, Math.cos(Math.toRadians(midpointLatitude)));
        double longitudeBuffer = CORRIDOR_BUFFER_METERS / (METERS_PER_LATITUDE_DEGREE * longitudeScale);
        return new BBox(
                Math.max(-180, minLon - longitudeBuffer),
                Math.min(180, maxLon + longitudeBuffer),
                Math.max(-90, minLat - latitudeBuffer),
                Math.min(90, maxLat + latitudeBuffer));
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}

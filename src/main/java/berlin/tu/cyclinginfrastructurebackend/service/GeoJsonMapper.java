package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.GeoJsonFeatureCollectionDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.GeoJsonFeatureCollectionDto.FeatureDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.GeoJsonFeatureCollectionDto.LineStringGeometryDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.SegmentTrafficStatsDto;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GeoJsonMapper {

    public GeoJsonFeatureCollectionDto toSegmentFeatureCollection(
            List<StreetSegment> segments,
            Map<Long, SegmentTrafficStatsDto> trafficStatsBySegmentId
    ) {
        return new GeoJsonFeatureCollectionDto(
                "FeatureCollection",
                segments.stream()
                        .filter(segment -> segment.getGeometry() != null)
                        .map(segment -> toFeature(segment, trafficStatsBySegmentId.get(segment.getId())))
                        .toList()
        );
    }

    private FeatureDto toFeature(StreetSegment segment, SegmentTrafficStatsDto trafficStats) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", segment.getId());
        properties.put("streetName", segment.getStreetName());
        properties.put("usageCount", segment.getUsageCount());
        properties.put("avoidanceCount", segment.getAvoidanceCount());
        properties.put("avoidanceRatio", segment.getAvoidanceRatio());
        properties.put("preferenceCount", segment.getPreferenceCount());
        properties.put("preferenceRatio", segment.getPreferenceRatio());
        properties.put("totalObservationCount", totalObservationCount(segment));
        properties.put("gradientPercent", segment.getGradientPercent());
        properties.put("traffic", trafficStats);

        return new FeatureDto(
                "Feature",
                toLineStringGeometry(segment),
                properties
        );
    }

    public LineStringGeometryDto toLineStringGeometry(StreetSegment segment) {
        if (segment.getGeometry() == null) {
            return null;
        }
        return new LineStringGeometryDto("LineString", coordinates(segment));
    }

    private List<List<Double>> coordinates(StreetSegment segment) {
        Coordinate[] coordinates = segment.getGeometry().getCoordinates();
        return List.of(coordinates).stream()
                .map(coordinate -> List.of(coordinate.x, coordinate.y))
                .toList();
    }

    private int totalObservationCount(StreetSegment segment) {
        return segment.getUsageCount() + segment.getAvoidanceCount();
    }
}

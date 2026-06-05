package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.GeoJsonFeatureCollectionDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.GeoJsonFeatureCollectionDto.FeatureDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.GeoJsonFeatureCollectionDto.LineStringGeometryDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.SegmentTrafficStatsDto;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GeoJsonMapper {

    /**
     * Converts street segments to a GeoJSON FeatureCollection suitable for map libraries.
     * Geometry is kept in GeoJSON lon/lat order, analysis values are exposed as feature properties.
     */
    public GeoJsonFeatureCollectionDto toSegmentFeatureCollection(List<StreetSegment> segments) {
        return toSegmentFeatureCollection(segments, Map.of());
    }

    public GeoJsonFeatureCollectionDto toSegmentFeatureCollection(List<StreetSegment> segments,
                                                                  Map<Long, SegmentTrafficStatsDto> trafficStatsBySegmentId) {
        List<FeatureDto> features = segments.stream()
                .filter(segment -> segment.getGeometry() != null && !segment.getGeometry().isEmpty())
                .map(segment -> toSegmentFeature(segment, trafficStatsBySegmentId.get(segment.getId())))
                .toList();

        return new GeoJsonFeatureCollectionDto("FeatureCollection", features);
    }

    private FeatureDto toSegmentFeature(StreetSegment segment, SegmentTrafficStatsDto trafficStats) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", segment.getId());
        properties.put("streetName", segment.getStreetName());
        properties.put("usageCount", segment.getUsageCount());
        properties.put("avoidanceCount", segment.getAvoidanceCount());
        properties.put("preferenceCount", segment.getPreferenceCount());
        properties.put("totalObservationCount", totalObservationCount(segment));
        properties.put("avoidanceRatio", segment.getAvoidanceRatio());
        properties.put("preferenceRatio", segment.getPreferenceRatio());
        properties.put("gradientPercent", segment.getGradientPercent());
        properties.put("trafficEnrichedEventCount", trafficStats != null ? trafficStats.trafficEnrichedEventCount() : 0L);
        properties.put("trafficMeasuredEventCount", trafficStats != null ? trafficStats.trafficMeasuredEventCount() : 0L);
        properties.put("trafficMeasuredShare", trafficStats != null ? trafficStats.measuredTrafficShare(eventObservationCount(segment)) : null);
        properties.put("dominantTrafficCondition", trafficStats != null ? trafficStats.dominantTrafficCondition() : null);
        properties.put("averageTrafficVolumeKfz", trafficStats != null ? trafficStats.averageTrafficVolumeKfz() : null);
        properties.put("averageTrafficSpeedKfz", trafficStats != null ? trafficStats.averageTrafficSpeedKfz() : null);
        properties.put("averageTrafficVolumePkw", trafficStats != null ? trafficStats.averageTrafficVolumePkw() : null);
        properties.put("averageTrafficSpeedPkw", trafficStats != null ? trafficStats.averageTrafficSpeedPkw() : null);
        properties.put("averageTrafficVolumeLkw", trafficStats != null ? trafficStats.averageTrafficVolumeLkw() : null);
        properties.put("averageTrafficSpeedLkw", trafficStats != null ? trafficStats.averageTrafficSpeedLkw() : null);

        return new FeatureDto(
                "Feature",
                toLineStringGeometry(segment.getGeometry()),
                properties
        );
    }

    private LineStringGeometryDto toLineStringGeometry(LineString lineString) {
        List<List<Double>> coordinates = new ArrayList<>();
        for (Coordinate coordinate : lineString.getCoordinates()) {
            coordinates.add(List.of(coordinate.x, coordinate.y));
        }
        return new LineStringGeometryDto("LineString", coordinates);
    }

    private int totalObservationCount(StreetSegment segment) {
        return segment.getUsageCount() + segment.getAvoidanceCount() + segment.getPreferenceCount();
    }

    private int eventObservationCount(StreetSegment segment) {
        return segment.getAvoidanceCount() + segment.getPreferenceCount();
    }
}

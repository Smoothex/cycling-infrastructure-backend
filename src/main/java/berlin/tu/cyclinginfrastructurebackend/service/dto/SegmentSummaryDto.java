package berlin.tu.cyclinginfrastructurebackend.service.dto;

import java.util.List;
import java.util.Map;

import berlin.tu.cyclinginfrastructurebackend.service.dto.api.GeoJsonFeatureCollectionDto.LineStringGeometryDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.SegmentTrafficStatsDto;

public record SegmentSummaryDto(
        Long id,
        String streetName,
        int usageCount,
        int avoidanceCount,
        Double avoidanceRatio,
        int preferenceCount,
        Double preferenceRatio,
        int totalObservationCount,
        Double gradientPercent,
        SegmentTrafficStatsDto traffic,
        long incidentCount,
        List<IncidentBreakdownDto> incidentBreakdown,
        List<ExternalFactorDto> externalFactors,
        LineStringGeometryDto geometry
) {

    public record IncidentBreakdownDto(String incidentType, long count) {}

    public record ExternalFactorDto(
            String factorType,
            String source,
            Long validFrom,
            Long validTo,
            Map<String, Object> metadata
    ) {}
}

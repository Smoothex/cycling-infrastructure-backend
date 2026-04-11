package berlin.tu.cyclinginfrastructurebackend.service.dto;

import java.util.List;
import java.util.Map;

public record SegmentSummaryDto(
        Long id,
        String streetName,
        int usageCount,
        int avoidanceCount,
        Double avoidanceRatio,
        int preferenceCount,
        Double preferenceRatio,
        long incidentCount,
        List<IncidentBreakdownDto> incidentBreakdown,
        List<ExternalFactorDto> externalFactors
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


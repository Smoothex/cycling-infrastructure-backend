package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.domain.Incident;
import berlin.tu.cyclinginfrastructurebackend.domain.SegmentExternalFactor;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ExternalFactorType;
import berlin.tu.cyclinginfrastructurebackend.repository.IncidentRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import berlin.tu.cyclinginfrastructurebackend.service.ExternalFactorService;
import berlin.tu.cyclinginfrastructurebackend.service.dto.SegmentSummaryDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.SegmentSummaryDto.ExternalFactorDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.SegmentSummaryDto.IncidentBreakdownDto;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/segments")
public class SegmentController {

    private static final double DEFAULT_INCIDENT_RADIUS_METERS = 25.0;

    private final StreetSegmentRepository segmentRepository;
    private final IncidentRepository incidentRepository;
    private final ExternalFactorService externalFactorService;

    public SegmentController(StreetSegmentRepository segmentRepository,
                             IncidentRepository incidentRepository,
                             ExternalFactorService externalFactorService) {
        this.segmentRepository = segmentRepository;
        this.incidentRepository = incidentRepository;
        this.externalFactorService = externalFactorService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<SegmentSummaryDto> getSegment(@PathVariable Long id) {
        return segmentRepository.findById(id)
                .map(segment -> ResponseEntity.ok(toSummary(segment)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Returns segments ranked by avoidance ratio, filtered by minimum thresholds.
     */
    @GetMapping
    public List<SegmentSummaryDto> getSuspiciousSegments(
            @RequestParam(defaultValue = "0.2") double minAvoidanceRatio,
            @RequestParam(defaultValue = "10") int minSampleSize,
            @RequestParam(defaultValue = "50") int limit) {

        return segmentRepository
                .findSuspiciousSegments(minAvoidanceRatio, minSampleSize, PageRequest.of(0, limit))
                .stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * Returns external factors for a specific segment, optionally filtered by type and time range.
     */
    @GetMapping("/{id}/factors")
    public ResponseEntity<List<ExternalFactorDto>> getSegmentFactors(
            @PathVariable Long id,
            @RequestParam(required = false) ExternalFactorType factorType,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {

        if (!segmentRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        List<SegmentExternalFactor> factors;
        if (from != null && to != null) {
            factors = externalFactorService.getFactorsInRange(id, from, to);
        } else if (factorType != null) {
            factors = externalFactorService.getFactorsByType(id, factorType);
        } else {
            factors = externalFactorService.getFactorsForSegment(id);
        }

        List<ExternalFactorDto> dtos = factors.stream()
                .map(this::toFactorDto)
                .toList();

        return ResponseEntity.ok(dtos);
    }

    private SegmentSummaryDto toSummary(StreetSegment segment) {
        List<Incident> nearbyIncidents = incidentRepository
                .findIncidentsNearSegment(segment.getId(), DEFAULT_INCIDENT_RADIUS_METERS);

        // Group incidents by type for the breakdown
        List<IncidentBreakdownDto> breakdown = nearbyIncidents.stream()
                .filter(i -> i.getIncidentType() != null)
                .collect(Collectors.groupingBy(i -> i.getIncidentType().name(), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new IncidentBreakdownDto(e.getKey(), e.getValue()))
                .toList();

        List<ExternalFactorDto> factors = externalFactorService.getFactorsForSegment(segment.getId())
                .stream()
                .map(this::toFactorDto)
                .toList();

        return new SegmentSummaryDto(
                segment.getId(),
                segment.getStreetName(),
                segment.getUsageCount(),
                segment.getAvoidanceCount(),
                segment.getAvoidanceRatio(),
                nearbyIncidents.size(),
                breakdown,
                factors
        );
    }

    private ExternalFactorDto toFactorDto(SegmentExternalFactor factor) {
        return new ExternalFactorDto(
                factor.getFactorType().name(),
                factor.getSource(),
                factor.getValidFrom(),
                factor.getValidTo(),
                factor.getMetadata()
        );
    }
}

package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.domain.Incident;
import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import berlin.tu.cyclinginfrastructurebackend.domain.SegmentExternalFactor;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ExternalFactorType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RideIntent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEnrichmentFilter;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEventType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficCondition;
import berlin.tu.cyclinginfrastructurebackend.repository.IncidentRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentEventRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentExternalFactorRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import berlin.tu.cyclinginfrastructurebackend.service.GeoJsonMapper;
import berlin.tu.cyclinginfrastructurebackend.service.dto.SegmentSummaryDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.SegmentSummaryDto.ExternalFactorDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.SegmentSummaryDto.IncidentBreakdownDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.GeoJsonFeatureCollectionDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.SegmentEventDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.SegmentTrafficStatsDto;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/segments")
public class SegmentController {

    private static final double DEFAULT_INCIDENT_RADIUS_METERS = 25.0;
    private static final int MAX_MAP_LIMIT = 10000;
    private static final int MAX_EVENT_LIMIT = 1000;

    private final StreetSegmentRepository segmentRepository;
    private final IncidentRepository incidentRepository;
    private final SegmentExternalFactorRepository segmentExternalFactorRepository;
    private final SegmentEventRepository segmentEventRepository;
    private final GeoJsonMapper geoJsonMapper;

    public SegmentController(StreetSegmentRepository segmentRepository,
                             IncidentRepository incidentRepository,
                             SegmentExternalFactorRepository segmentExternalFactorRepository,
                             SegmentEventRepository segmentEventRepository,
                             GeoJsonMapper geoJsonMapper) {
        this.segmentRepository = segmentRepository;
        this.incidentRepository = incidentRepository;
        this.segmentExternalFactorRepository = segmentExternalFactorRepository;
        this.segmentEventRepository = segmentEventRepository;
        this.geoJsonMapper = geoJsonMapper;
    }

    @GetMapping(value = "/geojson", produces = "application/geo+json")
    public GeoJsonFeatureCollectionDto getSegmentsGeoJson(
            @RequestParam(required = false, defaultValue = "0.2") Double minAvoidanceRatio,
            @RequestParam(required = false, defaultValue = "0.2") Double minPreferenceRatio,
            @RequestParam(defaultValue = "1") int minSampleSize,
            @RequestParam(required = false) String bbox,
            @RequestParam(defaultValue = "1000") int limit,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) RideIntent rideIntent,
            @RequestParam(required = false) TrafficCondition trafficCondition,
            @RequestParam(required = false) List<SegmentEnrichmentFilter> enrichmentFilters) {

        int safeLimit = limitToRange(limit, 1, MAX_MAP_LIMIT);
        EventCriteria criteria = EventCriteria.of(from, to, rideIntent, trafficCondition, enrichmentFilters);
        List<StreetSegment> segments;
        if (bbox != null && !bbox.isBlank()) {
            BoundingBox parsedBbox = parseBoundingBox(bbox);
            segments = segmentRepository.findSegmentsForMapWithinBbox(
                    minAvoidanceRatio,
                    minPreferenceRatio,
                    minSampleSize,
                    criteria.active(),
                    criteria.from(),
                    criteria.to(),
                    criteria.weatherEnriched(),
                    criteria.ohsomeEnriched(),
                    criteria.trafficEnriched(),
                    criteria.trafficMeasured(),
                    criteria.rideIntent(),
                    criteria.trafficCondition(),
                    parsedBbox.minLon(),
                    parsedBbox.minLat(),
                    parsedBbox.maxLon(),
                    parsedBbox.maxLat(),
                    safeLimit
            );
        } else {
            segments = segmentRepository.findSegmentsForMap(
                    minAvoidanceRatio,
                    minPreferenceRatio,
                    minSampleSize,
                    criteria.active(),
                    criteria.from(),
                    criteria.to(),
                    criteria.weatherEnriched(),
                    criteria.ohsomeEnriched(),
                    criteria.trafficEnriched(),
                    criteria.trafficMeasured(),
                    criteria.rideIntent(),
                    criteria.trafficCondition(),
                    safeLimit
            );
        }

        return geoJsonMapper.toSegmentFeatureCollection(segments, findTrafficStats(segments));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SegmentSummaryDto> getSegment(@PathVariable Long id) {
        return segmentRepository.findById(id)
                .map(segment -> ResponseEntity.ok(toSummary(segment)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Returns segments ranked by avoidance ratio, filtered by minimum thresholds.
     * With event-level filters, only segments carrying at least one event matching all
     * of the supplied time, enrichment, ride-intent, and traffic criteria are returned.
     */
    @GetMapping
    public List<SegmentSummaryDto> getSuspiciousSegments(
            @RequestParam(defaultValue = "0.2") double minAvoidanceRatio,
            @RequestParam(defaultValue = "10") int minSampleSize,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) RideIntent rideIntent,
            @RequestParam(required = false) TrafficCondition trafficCondition,
            @RequestParam(required = false) List<SegmentEnrichmentFilter> enrichmentFilters) {

        EventCriteria criteria = EventCriteria.of(from, to, rideIntent, trafficCondition, enrichmentFilters);
        List<StreetSegment> segments = segmentRepository.findSuspiciousSegments(
                minAvoidanceRatio,
                minSampleSize,
                criteria.active(),
                criteria.from(),
                criteria.to(),
                criteria.weatherEnriched(),
                criteria.ohsomeEnriched(),
                criteria.trafficEnriched(),
                criteria.trafficMeasured(),
                criteria.rideIntent(),
                criteria.trafficCondition(),
                limitToRange(limit, 1, MAX_MAP_LIMIT));
        Map<Long, SegmentTrafficStatsDto> trafficStatsBySegmentId = findTrafficStats(segments);

        return segments.stream()
                .map(segment -> toSummary(segment, trafficStatsBySegmentId.get(segment.getId())))
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
            factors = segmentExternalFactorRepository.findOverlapping(id, from, to);
        } else if (factorType != null) {
            factors = segmentExternalFactorRepository.findBySegmentIdAndFactorType(id, factorType);
        } else {
            factors = segmentExternalFactorRepository.findBySegmentId(id);
        }

        List<ExternalFactorDto> dtos = factors.stream()
                .map(this::toFactorDto)
                .toList();

        return ResponseEntity.ok(dtos);
    }

    private SegmentSummaryDto toSummary(StreetSegment segment) {
        return toSummary(
                segment,
                findTrafficStats(List.of(segment)).get(segment.getId()),
                geoJsonMapper.toLineStringGeometry(segment)
        );
    }

    @GetMapping("/{id}/events")
    public ResponseEntity<List<SegmentEventDto>> getSegmentEvents(
            @PathVariable Long id,
            @RequestParam(required = false) SegmentEventType eventType,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) RideIntent rideIntent,
            @RequestParam(required = false) TrafficCondition trafficCondition,
            @RequestParam(required = false) List<SegmentEnrichmentFilter> enrichmentFilters) {

        if (!segmentRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        List<SegmentEvent> events = segmentEventRepository.findSegmentEventsForApi(
                id,
                eventType,
                from,
                to,
                hasFilter(enrichmentFilters, SegmentEnrichmentFilter.WEATHER_ENRICHED),
                hasFilter(enrichmentFilters, SegmentEnrichmentFilter.OHSOME_ENRICHED),
                hasFilter(enrichmentFilters, SegmentEnrichmentFilter.TRAFFIC_ENRICHED),
                hasFilter(enrichmentFilters, SegmentEnrichmentFilter.TRAFFIC_MEASURED),
                rideIntent,
                trafficCondition,
                PageRequest.of(0, limitToRange(limit, 1, MAX_EVENT_LIMIT))
        );

        return ResponseEntity.ok(events.stream()
                .map(SegmentEventDto::from)
                .toList());
    }

    private SegmentSummaryDto toSummary(StreetSegment segment, SegmentTrafficStatsDto trafficStats) {
        // list responses omit geometry to keep them small
        return toSummary(segment, trafficStats, null);
    }

    private SegmentSummaryDto toSummary(StreetSegment segment,
                                        SegmentTrafficStatsDto trafficStats,
                                        GeoJsonFeatureCollectionDto.LineStringGeometryDto geometry) {
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

        List<ExternalFactorDto> factors = segmentExternalFactorRepository.findBySegmentId(segment.getId())
                .stream()
                .map(this::toFactorDto)
                .toList();

        return new SegmentSummaryDto(
                segment.getId(),
                segment.getStreetName(),
                segment.getUsageCount(),
                segment.getAvoidanceCount(),
                segment.getAvoidanceRatio(),
                segment.getPreferenceCount(),
                segment.getPreferenceRatio(),
                totalObservationCount(segment),
                segment.getGradientPercent(),
                trafficStats,
                nearbyIncidents.size(),
                breakdown,
                factors,
                geometry
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

    private int totalObservationCount(StreetSegment segment) {
        return segment.getUsageCount() + segment.getAvoidanceCount();
    }

    private Map<Long, SegmentTrafficStatsDto> findTrafficStats(List<StreetSegment> segments) {
        List<Long> segmentIds = segments.stream()
                .map(StreetSegment::getId)
                .toList();

        if (segmentIds.isEmpty()) {
            return Map.of();
        }

        return segmentEventRepository.findTrafficStatsForSegments(segmentIds).stream()
                .map(SegmentTrafficStatsDto::from)
                .collect(Collectors.toMap(SegmentTrafficStatsDto::segmentId, stats -> stats));
    }

    private int limitToRange(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private boolean hasFilter(List<SegmentEnrichmentFilter> filters, SegmentEnrichmentFilter filter) {
        return filters != null && filters.contains(filter);
    }

    /**
     * Event-level segment qualification criteria shared by the list and geojson
     * endpoints: a segment qualifies when at least one of its events matches the
     * time window and every selected enrichment at once. The window defaults to
     * an all-time range so the repository queries need no null handling.
     */
    private record EventCriteria(
            boolean active,
            long from,
            long to,
            boolean weatherEnriched,
            boolean ohsomeEnriched,
            boolean trafficEnriched,
            boolean trafficMeasured,
            String rideIntent,
            String trafficCondition
    ) {
        private static EventCriteria of(Long from, Long to, RideIntent rideIntent,
                                        TrafficCondition trafficCondition,
                                        List<SegmentEnrichmentFilter> filters) {
            boolean weatherEnriched = has(filters, SegmentEnrichmentFilter.WEATHER_ENRICHED);
            boolean ohsomeEnriched = has(filters, SegmentEnrichmentFilter.OHSOME_ENRICHED);
            boolean trafficEnriched = has(filters, SegmentEnrichmentFilter.TRAFFIC_ENRICHED);
            boolean trafficMeasured = has(filters, SegmentEnrichmentFilter.TRAFFIC_MEASURED);
            return new EventCriteria(
                    from != null || to != null
                            || weatherEnriched || ohsomeEnriched || trafficEnriched || trafficMeasured
                            || rideIntent != null || trafficCondition != null,
                    from != null ? from : 0L,
                    to != null ? to : Long.MAX_VALUE,
                    weatherEnriched,
                    ohsomeEnriched,
                    trafficEnriched,
                    trafficMeasured,
                    rideIntent != null ? rideIntent.name() : "",
                    trafficCondition != null ? trafficCondition.name() : ""
            );
        }

        private static boolean has(List<SegmentEnrichmentFilter> filters, SegmentEnrichmentFilter filter) {
            return filters != null && filters.contains(filter);
        }
    }

    private BoundingBox parseBoundingBox(String bbox) {
        String[] parts = bbox.split(",");
        if (parts.length != 4) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "bbox must contain minLon,minLat,maxLon,maxLat"
            );
        }

        try {
            double minLon = Double.parseDouble(parts[0].trim());
            double minLat = Double.parseDouble(parts[1].trim());
            double maxLon = Double.parseDouble(parts[2].trim());
            double maxLat = Double.parseDouble(parts[3].trim());

            if (minLon >= maxLon || minLat >= maxLat) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "bbox minimum coordinates must be smaller than maximum coordinates"
                );
            }

            return new BoundingBox(minLon, minLat, maxLon, maxLat);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "bbox must contain numeric minLon,minLat,maxLon,maxLat"
            );
        }
    }

    private record BoundingBox(double minLon, double minLat, double maxLon, double maxLat) {
    }
}

package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.RideIntent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEnrichmentFilter;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEventType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficCondition;
import berlin.tu.cyclinginfrastructurebackend.service.ApiAnalyticsService;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.AnalysisDimension;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.AnalyticsContextDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.CorridorRankingDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.DimensionBucketDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.InfrastructureSignalsDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.PipelineStatusDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.ProcessingSummaryDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final ApiAnalyticsService analyticsService;

    public AnalyticsController(ApiAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/summary")
    public ProcessingSummaryDto getSummary() {
        return analyticsService.getProcessingSummary();
    }

    @GetMapping("/pipeline-status")
    public PipelineStatusDto getPipelineStatus() {
        return analyticsService.getPipelineStatus();
    }

    @GetMapping("/distribution")
    public List<DimensionBucketDto> getDistribution(
            @RequestParam(defaultValue = "EVENT_TYPE") AnalysisDimension dimension,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) SegmentEventType eventType,
            @RequestParam(required = false) RideIntent rideIntent,
            @RequestParam(required = false) TrafficCondition trafficCondition,
            @RequestParam(required = false) List<SegmentEnrichmentFilter> enrichmentFilters,
            @RequestParam(defaultValue = "50") int limit) {

        return analyticsService.getEventDistribution(
                dimension, from, to, eventType, rideIntent, trafficCondition, enrichmentFilters, limit);
    }

    @GetMapping("/context")
    public AnalyticsContextDto getContext(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) RideIntent rideIntent) {

        return analyticsService.getAnalyticsContext(from, to, rideIntent);
    }

    @GetMapping("/corridors")
    public List<CorridorRankingDto> getCorridors(
            @RequestParam(defaultValue = "AVOIDANCE") SegmentEventType rank,
            @RequestParam(defaultValue = "8") int limit,
            @RequestParam(defaultValue = "5") int minRideCount,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) RideIntent rideIntent) {

        return analyticsService.getCorridorRanking(rank, minRideCount, limit, from, to, rideIntent);
    }

    @GetMapping("/infrastructure-signals")
    public InfrastructureSignalsDto getInfrastructureSignals(
            @RequestParam(defaultValue = "SURFACE") AnalysisDimension dimension,
            @RequestParam(defaultValue = "20") int minRideCount,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) RideIntent rideIntent) {

        return analyticsService.getInfrastructureSignals(
                dimension, minRideCount, limit, from, to, rideIntent);
    }
}

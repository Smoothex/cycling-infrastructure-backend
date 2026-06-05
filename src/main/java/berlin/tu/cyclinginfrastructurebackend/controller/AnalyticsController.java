package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEventType;
import berlin.tu.cyclinginfrastructurebackend.service.ApiAnalyticsService;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.AnalysisDimension;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.DimensionBucketDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.ProcessingSummaryDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.TimeBucket;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.TimeSeriesBucketDto;
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

    /**
     * Returns high-level processing and enrichment counters for backend health and progress views.
     */
    @GetMapping("/summary")
    public ProcessingSummaryDto getSummary() {
        return analyticsService.getProcessingSummary();
    }

    /**
     * Returns event counts grouped by a selected analysis dimension.
     */
    @GetMapping("/distribution")
    public List<DimensionBucketDto> getDistribution(
            @RequestParam(defaultValue = "EVENT_TYPE") AnalysisDimension dimension,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) SegmentEventType eventType,
            @RequestParam(defaultValue = "50") int limit) {

        return analyticsService.getEventDistribution(dimension, from, to, eventType, limit);
    }

    /**
     * Returns event counts over time for change-detection views.
     */
    @GetMapping("/time-series")
    public List<TimeSeriesBucketDto> getTimeSeries(
            @RequestParam(defaultValue = "MONTH") TimeBucket bucket,
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) SegmentEventType eventType) {

        return analyticsService.getEventTimeSeries(bucket, from, to, eventType);
    }
}

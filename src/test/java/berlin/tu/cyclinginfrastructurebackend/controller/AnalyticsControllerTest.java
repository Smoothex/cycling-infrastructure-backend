package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.RideIntent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEventType;
import berlin.tu.cyclinginfrastructurebackend.service.ApiAnalyticsService;
import berlin.tu.cyclinginfrastructurebackend.service.CorridorGeometryService;
import berlin.tu.cyclinginfrastructurebackend.service.RouteComparisonExportService;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.AnalysisDimension;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.AnalyticsContextDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.AnalyticsFilterOptionsDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.CorridorRankingDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.CorridorGeometryDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.DetourImpactDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.InfrastructureSignalsDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.ProcessingSummaryDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteComparisonSummaryDto;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalyticsControllerTest {

    private final ApiAnalyticsService analyticsService = mock(ApiAnalyticsService.class);
    private final CorridorGeometryService corridorGeometryService = mock(CorridorGeometryService.class);
    private final RouteComparisonExportService routeComparisonExportService = mock(RouteComparisonExportService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AnalyticsController(
                    analyticsService, corridorGeometryService, routeComparisonExportService))
            .build();

    @Test
    void summaryReturnsProcessingCounters() throws Exception {
        when(analyticsService.getProcessingSummary()).thenReturn(new ProcessingSummaryDto(
                10, Map.of("PROCESSED", 7L), Map.of("LOCAL_DETOUR", 4L),
                20, 12, 30, 1000L, 2000L,
                Map.of("AVOIDANCE", 18L, "PREFERENCE", 12L), 5, 6, 7, 8, 9, 10));

        mockMvc.perform(get("/api/analytics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRides").value(10))
                .andExpect(jsonPath("$.rideStatusCounts.PROCESSED").value(7))
                .andExpect(jsonPath("$.routeComparisonTypeCounts.LOCAL_DETOUR").value(4))
                .andExpect(jsonPath("$.roadDisruptionAffectedEvents").value(10));
    }

    @Test
    void contextReturnsFilterAwareEvidence() throws Exception {
        when(analyticsService.getAnalyticsContext(any(), any(), any())).thenReturn(
                new AnalyticsContextDto(20, 300, 120, 180, 1000L, 2000L));

        mockMvc.perform(get("/api/analytics/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchingRideCount").value(20))
                .andExpect(jsonPath("$.matchingEventCount").value(300));

        verify(analyticsService).getAnalyticsContext(eq(null), eq(null), eq(null));
    }

    @Test
    void filterOptionsReturnObservedCategoriesWithoutDistributionMetrics() throws Exception {
        when(analyticsService.getFilterOptions()).thenReturn(new AnalyticsFilterOptionsDto(
                List.of("COMMUTE", "UNKNOWN"), List.of("LIGHT", "HEAVY")));

        mockMvc.perform(get("/api/analytics/filter-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rideIntents[0]").value("COMMUTE"))
                .andExpect(jsonPath("$.rideIntents[1]").value("UNKNOWN"))
                .andExpect(jsonPath("$.trafficConditions[0]").value("LIGHT"))
                .andExpect(jsonPath("$.trafficConditions[1]").value("HEAVY"))
                .andExpect(jsonPath("$.totalCount").doesNotExist());
    }

    @Test
    void routeComparisonsReturnFilterAwareRideCounts() throws Exception {
        when(analyticsService.getRouteComparisonSummary(any(), any(), any())).thenReturn(
                new RouteComparisonSummaryDto(12L, Map.of(
                        "EQUIVALENT_ROUTE", 8L,
                        "LOCAL_DETOUR", 4L,
                        "CORRIDOR_ALTERNATIVE", 0L),
                        0.10,
                        500.0,
                        0.30,
                        List.of(new DetourImpactDto(
                                RouteComparisonType.LOCAL_DETOUR, 4L, 11.25, 14.5, 19.75))));

        mockMvc.perform(get("/api/analytics/route-comparisons")
                        .param("from", "1000")
                        .param("to", "2000")
                        .param("rideIntent", "COMMUTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classifiedRideCount").value(12))
                .andExpect(jsonPath("$.routeComparisonTypeCounts.EQUIVALENT_ROUTE").value(8))
                .andExpect(jsonPath("$.routeComparisonTypeCounts.CORRIDOR_ALTERNATIVE").value(0))
                .andExpect(jsonPath("$.detourThresholdRatio").value(0.10))
                .andExpect(jsonPath("$.maximumEquivalentExcessDistanceMeters").value(500.0))
                .andExpect(jsonPath("$.minimumOverlapRatio").value(0.30))
                .andExpect(jsonPath("$.detourImpact[0].routeComparisonType").value("LOCAL_DETOUR"))
                .andExpect(jsonPath("$.detourImpact[0].eligibleRideCount").value(4))
                .andExpect(jsonPath("$.detourImpact[0].lowerQuartilePercent").value(11.25))
                .andExpect(jsonPath("$.detourImpact[0].medianPercent").value(14.5))
                .andExpect(jsonPath("$.detourImpact[0].upperQuartilePercent").value(19.75));

        verify(analyticsService).getRouteComparisonSummary(
                eq(1000L), eq(2000L), eq(RideIntent.COMMUTE));
    }

    @Test
    void corridorsUsePlannerFocusedDefaults() throws Exception {
        when(analyticsService.getCorridorRanking(any(), anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(List.of(new CorridorRankingDto(
                        "Chausseestraße", 134, 80, 1491, 600, 42, 68,
                        13.38, 52.52, 13.39, 52.55, 123L, List.of(101L, 205L))));

        mockMvc.perform(get("/api/analytics/corridors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].streetName").value("Chausseestraße"))
                .andExpect(jsonPath("$[0].avoidanceRideCount").value(134))
                .andExpect(jsonPath("$[0].scaryIncidentCount").value(68))
                .andExpect(jsonPath("$[0].segmentIds[0]").value(101));

        verify(analyticsService).getCorridorRanking(
                eq(SegmentEventType.AVOIDANCE), eq(5), eq(8),
                eq(null), eq(null), eq(null));
    }

    @Test
    void corridorGeometryUsesTheSelectedStreetBounds() throws Exception {
        when(corridorGeometryService.getCorridorGeometry(
                eq("Schönhauser Allee"), eq(13.4), eq(52.52), eq(13.42), eq(52.54)))
                .thenReturn(new CorridorGeometryDto(
                        "Schönhauser Allee",
                        List.of(101L, 102L),
                        new CorridorGeometryDto.MultiLineStringGeometry(List.of(
                                List.of(List.of(13.4, 52.52), List.of(13.42, 52.54))))));

        mockMvc.perform(get("/api/analytics/corridor-geometry")
                        .param("streetName", "Schönhauser Allee")
                        .param("minLon", "13.4")
                        .param("minLat", "52.52")
                        .param("maxLon", "13.42")
                        .param("maxLat", "52.54"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streetName").value("Schönhauser Allee"))
                .andExpect(jsonPath("$.segmentIds[1]").value(102))
                .andExpect(jsonPath("$.geometry.type").value("MultiLineString"));

        verify(corridorGeometryService).getCorridorGeometry(
                eq("Schönhauser Allee"), eq(13.4), eq(52.52), eq(13.42), eq(52.54));
    }

    @Test
    void infrastructureSignalsUseSurfaceDefaults() throws Exception {
        when(analyticsService.getInfrastructureSignals(any(), anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(new InfrastructureSignalsDto(
                        AnalysisDimension.SURFACE, 1000, 400, 0.4, 0.35,
                        List.of(new InfrastructureSignalsDto.InfrastructureSignalBucketDto(
                                "sett", 80, 20, 100, 0.8, 45.0))));

        mockMvc.perform(get("/api/analytics/infrastructure-signals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dimension").value("SURFACE"))
                .andExpect(jsonPath("$.coverageShare").value(0.4))
                .andExpect(jsonPath("$.buckets[0].percentagePointDifference").value(45.0));

        verify(analyticsService).getInfrastructureSignals(
                eq(AnalysisDimension.SURFACE), eq(20), eq(10),
                eq(null), eq(null), eq(null));
    }

    @Test
    void calibrationExportReturnsDownloadableCsvAndClampsPerType() throws Exception {
        when(routeComparisonExportService.exportCalibrationSample(1000L, 2000L, 200))
                .thenReturn("ride_id,review_label\n");

        mockMvc.perform(get("/api/analytics/route-comparisons/calibration.csv")
                        .param("from", "1000")
                        .param("to", "2000")
                        .param("perType", "500"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition",
                                "attachment; filename=\"route-comparison-calibration.csv\""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith("text/csv"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string("ride_id,review_label\n"));

        verify(routeComparisonExportService).exportCalibrationSample(1000L, 2000L, 200);
    }
}

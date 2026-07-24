package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEventType;
import berlin.tu.cyclinginfrastructurebackend.service.ApiAnalyticsService;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.AnalysisDimension;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.AnalyticsContextDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.CorridorRankingDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.InfrastructureSignalsDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.ProcessingSummaryDto;
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
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AnalyticsController(analyticsService))
            .build();

    @Test
    void summaryReturnsProcessingCounters() throws Exception {
        when(analyticsService.getProcessingSummary()).thenReturn(new ProcessingSummaryDto(
                10, Map.of("PROCESSED", 7L), 20, 12, 30, 1000L, 2000L,
                Map.of("AVOIDANCE", 18L, "PREFERENCE", 12L), 5, 6, 7, 8, 9));

        mockMvc.perform(get("/api/analytics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRides").value(10))
                .andExpect(jsonPath("$.rideStatusCounts.PROCESSED").value(7));
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
    void corridorsUsePlannerFocusedDefaults() throws Exception {
        when(analyticsService.getCorridorRanking(any(), anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(List.of(new CorridorRankingDto(
                        "Chausseestraße", 134, 80, 1491, 600, 42, 68,
                        13.38, 52.52, 13.39, 52.55, 123L)));

        mockMvc.perform(get("/api/analytics/corridors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].streetName").value("Chausseestraße"))
                .andExpect(jsonPath("$[0].avoidanceRideCount").value(134))
                .andExpect(jsonPath("$[0].scaryIncidentCount").value(68));

        verify(analyticsService).getCorridorRanking(
                eq(SegmentEventType.AVOIDANCE), eq(5), eq(8),
                eq(null), eq(null), eq(null));
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
}

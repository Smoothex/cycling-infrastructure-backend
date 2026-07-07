package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.service.ApiAnalyticsService;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.AnalysisDimension;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.ProcessingSummaryDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.TimeBucket;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

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
                10,
                Map.of("PROCESSED", 7L),
                20,
                12,
                30,
                1000L,
                2000L,
                Map.of("AVOIDANCE", 18L, "PREFERENCE", 12L),
                5,
                6,
                7,
                8,
                9
        ));

        mockMvc.perform(get("/api/analytics/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRides").value(10))
                .andExpect(jsonPath("$.rideStatusCounts.PROCESSED").value(7))
                .andExpect(jsonPath("$.segmentEventTypeCounts.AVOIDANCE").value(18));
    }

    @Test
    void distributionUsesDefaultDimensionAndLimit() throws Exception {
        mockMvc.perform(get("/api/analytics/distribution"))
                .andExpect(status().isOk());

        verify(analyticsService).getEventDistribution(
                eq(AnalysisDimension.EVENT_TYPE),
                eq(null),
                eq(null),
                eq(null),
                eq(50)
        );
    }

    @Test
    void timeSeriesUsesDefaultMonthBucket() throws Exception {
        mockMvc.perform(get("/api/analytics/time-series"))
                .andExpect(status().isOk());

        verify(analyticsService).getEventTimeSeries(
                eq(TimeBucket.MONTH),
                eq(null),
                eq(null),
                eq(null)
        );
    }
}

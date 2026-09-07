package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.ManualRouteComparisonClassification;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;
import berlin.tu.cyclinginfrastructurebackend.service.RouteComparisonReviewService;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteReviewDetailDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteReviewDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteReviewSampleDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteReviewSampleItemDto;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RouteComparisonReviewControllerTest {

    private final RouteComparisonReviewService service = mock(RouteComparisonReviewService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new RouteComparisonReviewController(service))
            .build();

    @Test
    void sampleEndpointExplainsThatBalancedSampleIsNotPrevalenceRepresentative() throws Exception {
        UUID rideId = UUID.randomUUID();
        when(service.getSample()).thenReturn(new RouteReviewSampleDto(
                30,
                1,
                0,
                false,
                List.of(new RouteReviewSampleItemDto(
                        rideId, 1, 1, RouteComparisonType.EQUIVALENT_ROUTE,
                        1_700_000_000_000L, "COMMUTE", "CITY_TREKKING_BIKE",
                        1_000.0, 980.0, 20.0, 0.02, 0.95, null))));

        mockMvc.perform(get("/api/route-comparisons/review-sample"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleSizePerType").value(30))
                .andExpect(jsonPath("$.representativeOfPrevalence").value(false))
                .andExpect(jsonPath("$.items[0].automatedClassification")
                        .value("EQUIVALENT_ROUTE"));
    }

    @Test
    void detailEndpointExposesTheCompleteClassificationPolicy() throws Exception {
        UUID rideId = UUID.randomUUID();
        when(service.getDetail(rideId)).thenReturn(new RouteReviewDetailDto(
                rideId,
                1,
                1,
                RouteComparisonType.LOCAL_DETOUR,
                1_700_000_000_000L,
                1_700_000_060_000L,
                60L,
                "COMMUTE",
                "CITY_TREKKING_BIKE",
                1_200.0,
                1_000.0,
                200.0,
                0.20,
                0.55,
                7.5,
                42L,
                0.10,
                500.0,
                0.30,
                null,
                null,
                List.of(),
                null));

        mockMvc.perform(get("/api/route-comparisons/review-sample/{rideId}", rideId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detourThresholdRatio").value(0.10))
                .andExpect(jsonPath("$.maximumEquivalentExcessDistanceMeters").value(500.0))
                .andExpect(jsonPath("$.minimumOverlapRatio").value(0.30));

        verify(service).getDetail(rideId);
    }

    @Test
    void rideEndpointReturnsAnArbitraryEligibleRouteComparison() throws Exception {
        UUID rideId = UUID.randomUUID();
        when(service.getRideDetail(rideId)).thenReturn(new RouteReviewDetailDto(
                rideId,
                0,
                0,
                RouteComparisonType.CORRIDOR_ALTERNATIVE,
                1_700_000_000_000L,
                1_700_000_060_000L,
                60L,
                "COMMUTE",
                "CITY_TREKKING_BIKE",
                1_500.0,
                1_000.0,
                500.0,
                0.50,
                0.20,
                7.5,
                42L,
                0.10,
                500.0,
                0.30,
                null,
                null,
                List.of(),
                null));

        mockMvc.perform(get("/api/route-comparisons/rides/{rideId}", rideId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rideId").value(rideId.toString()))
                .andExpect(jsonPath("$.sampleOrder").value(0))
                .andExpect(jsonPath("$.classSampleRank").value(0))
                .andExpect(jsonPath("$.automatedClassification")
                        .value("CORRIDOR_ALTERNATIVE"));

        verify(service).getRideDetail(rideId);
    }

    @Test
    void reviewEndpointAcceptsManualClassification() throws Exception {
        UUID rideId = UUID.randomUUID();
        when(service.saveReview(any(), any())).thenReturn(new RouteReviewDto(
                ManualRouteComparisonClassification.LOCAL_DETOUR,
                List.of(),
                null,
                1_700_000_000_000L));

        mockMvc.perform(put("/api/route-comparisons/review-sample/{rideId}/review", rideId)
                        .contentType("application/json")
                        .content("""
                                {"manualClassification":"LOCAL_DETOUR","issueCodes":[],"notes":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.manualClassification").value("LOCAL_DETOUR"));

        verify(service).saveReview(any(), any());
    }

    @Test
    void csvEndpointReturnsDownload() throws Exception {
        when(service.exportSampleCsv()).thenReturn("ride_id,manual_classification\n");

        mockMvc.perform(get("/api/route-comparisons/review-sample.csv"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Disposition",
                        "attachment; filename=\"route-comparison-review-sample.csv\""))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string("ride_id,manual_classification\n"));
    }
}

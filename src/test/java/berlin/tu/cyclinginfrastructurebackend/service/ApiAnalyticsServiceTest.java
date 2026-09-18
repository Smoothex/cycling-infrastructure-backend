package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.RideIntent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEventType;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.AnalyticsReadRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentEventRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.AnalysisDimension;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.AnalyticsContextDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.AnalyticsFilterOptionsDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.CorridorRankingDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.DetourImpactDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.InfrastructureSignalsDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.ProcessingSummaryDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteComparisonSummaryDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ApiAnalyticsServiceTest {

    private final EntityManager entityManager = mock(EntityManager.class);
    private final RideRepository rideRepository = mock(RideRepository.class);
    private final StreetSegmentRepository streetSegmentRepository = mock(StreetSegmentRepository.class);
    private final SegmentEventRepository segmentEventRepository = mock(SegmentEventRepository.class);
    private final AnalyticsReadRepository analyticsReadRepository = mock(AnalyticsReadRepository.class);
    private final ApiAnalyticsService service = new ApiAnalyticsService(
            rideRepository,
            streetSegmentRepository,
            segmentEventRepository,
            entityManager,
            analyticsReadRepository,
            0.10,
            500.0,
            0.30);

    @BeforeEach
    void resetEntityManager() {
        org.mockito.Mockito.reset(entityManager);
        org.mockito.Mockito.reset(rideRepository);
        org.mockito.Mockito.reset(streetSegmentRepository);
        org.mockito.Mockito.reset(segmentEventRepository);
        org.mockito.Mockito.reset(analyticsReadRepository);
    }

    @Test
    void processingSummarySeparatesStatusAndRouteComparisonCounts() {
        when(analyticsReadRepository.rideCounts()).thenReturn(new AnalyticsReadRepository.RideCounts(
                10L, Map.of("PROCESSED", 7L), Map.of(
                "EQUIVALENT_ROUTE", 0L, "LOCAL_DETOUR", 4L, "CORRIDOR_ALTERNATIVE", 0L)));
        when(analyticsReadRepository.eventCounts()).thenReturn(new AnalyticsReadRepository.EventCounts(
                30L, 1000L, 2000L, 18L, 12L, 5L, 6L, 7L, 8L, 9L));
        when(analyticsReadRepository.segmentCounts()).thenReturn(new AnalyticsReadRepository.SegmentCounts(20L, 12L));
        when(segmentEventRepository.countRoadDisruptionAffectedEvents()).thenReturn(11L);

        ProcessingSummaryDto result = service.getProcessingSummary();

        assertThat(result.rideStatusCounts().get("PROCESSED")).isEqualTo(7L);
        assertThat(result.routeComparisonTypeCounts().get("LOCAL_DETOUR")).isEqualTo(4L);
        assertThat(result.routeComparisonTypeCounts()).containsKeys(
                "EQUIVALENT_ROUTE", "LOCAL_DETOUR", "CORRIDOR_ALTERNATIVE");
        assertThat(result.roadDisruptionAffectedEvents()).isEqualTo(11L);
        assertThat(result).isEqualTo(new ProcessingSummaryDto(
                10L, Map.of("PROCESSED", 7L), Map.of(
                "EQUIVALENT_ROUTE", 0L, "LOCAL_DETOUR", 4L, "CORRIDOR_ALTERNATIVE", 0L),
                20L, 12L, 30L, 1000L, 2000L,
                Map.of("AVOIDANCE", 18L, "PREFERENCE", 12L), 5L, 6L, 7L, 8L, 9L, 11L));
        verify(analyticsReadRepository).rideCounts();
        verify(analyticsReadRepository).eventCounts();
        verify(analyticsReadRepository).segmentCounts();
        verify(segmentEventRepository).countRoadDisruptionAffectedEvents();
        verifyNoMoreInteractions(analyticsReadRepository, segmentEventRepository);
        verifyNoInteractions(rideRepository, streetSegmentRepository, entityManager);
    }

    @Test
    void filterOptionsUseOnlyTheLightweightReadRepository() {
        var options = new AnalyticsFilterOptionsDto(List.of("COMMUTE", "UNKNOWN"), List.of("LIGHT"));
        when(analyticsReadRepository.filterOptions()).thenReturn(options);

        assertThat(service.getFilterOptions()).isEqualTo(options);
        verifyNoInteractions(rideRepository, streetSegmentRepository, segmentEventRepository, entityManager);
    }

    @Test
    void routeComparisonSummaryFiltersRidesAndIncludesZeroCountTypes() {
        when(rideRepository.countRouteComparisonTypes(1000L, 2000L, RideIntent.COMMUTE))
                .thenReturn(List.<Object[]>of(
                        new Object[]{RouteComparisonType.EQUIVALENT_ROUTE, 8L},
                        new Object[]{RouteComparisonType.LOCAL_DETOUR, 4L}));
        when(rideRepository.findDetourImpactStats(1000L, 2000L, "COMMUTE"))
                .thenReturn(List.<Object[]>of(
                        new Object[]{"LOCAL_DETOUR", 4L, 11.25, 14.5, 19.75},
                        new Object[]{"CORRIDOR_ALTERNATIVE", 3L, 8.0, 17.0, 31.0}));

        RouteComparisonSummaryDto result = service.getRouteComparisonSummary(
                1000L, 2000L, RideIntent.COMMUTE);

        assertThat(result.classifiedRideCount()).isEqualTo(12L);
        assertThat(result.routeComparisonTypeCounts()).containsEntry("EQUIVALENT_ROUTE", 8L);
        assertThat(result.routeComparisonTypeCounts()).containsEntry("LOCAL_DETOUR", 4L);
        assertThat(result.routeComparisonTypeCounts()).containsEntry("CORRIDOR_ALTERNATIVE", 0L);
        assertThat(result.detourThresholdRatio()).isEqualTo(0.10);
        assertThat(result.maximumEquivalentExcessDistanceMeters()).isEqualTo(500.0);
        assertThat(result.minimumOverlapRatio()).isEqualTo(0.30);
        assertThat(result.detourImpact()).containsExactly(
                new DetourImpactDto(RouteComparisonType.LOCAL_DETOUR, 4L, 11.25, 14.5, 19.75),
                new DetourImpactDto(RouteComparisonType.CORRIDOR_ALTERNATIVE, 3L, 8.0, 17.0, 31.0));
        verify(rideRepository).countRouteComparisonTypes(
                eq(1000L), eq(2000L), eq(RideIntent.COMMUTE));
        verify(rideRepository).findDetourImpactStats(eq(1000L), eq(2000L), eq("COMMUTE"));
    }

    @Test
    void routeComparisonSummaryRejectsAnInvalidDateRange() {
        assertThatThrownBy(() -> service.getRouteComparisonSummary(2000L, 1000L, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void detourImpactQueryUsesPercentilesFormulaAndEligibilityFilters() throws NoSuchMethodException {
        org.springframework.data.jpa.repository.Query query = RideRepository.class
                .getMethod("findDetourImpactStats", long.class, long.class, String.class)
                .getAnnotation(org.springframework.data.jpa.repository.Query.class);

        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value())
                .contains("PERCENTILE_CONT(0.25)", "PERCENTILE_CONT(0.50)", "PERCENTILE_CONT(0.75)")
                .contains("((actual_distance - shortest_path_distance) / shortest_path_distance) * 100.0")
                .contains("actual_distance IS NOT NULL", "shortest_path_distance IS NOT NULL")
                .contains("shortest_path_distance > 0", "start_time >= :from", "start_time <= :to")
                .contains("ride_intent = CAST(:rideIntent AS varchar)")
                .contains("'LOCAL_DETOUR', 'CORRIDOR_ALTERNATIVE'")
                .doesNotContain("'EQUIVALENT_ROUTE'");
    }

    @Test
    void contextMapsDistinctRideAndEventEvidence() {
        Query query = fluentQuery();
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(new Object[]{20L, 300L, 120L, 180L, 1000L, 2000L});

        AnalyticsContextDto result = service.getAnalyticsContext(null, null, null);

        assertThat(result.matchingRideCount()).isEqualTo(20);
        assertThat(result.matchingEventCount()).isEqualTo(300);
        assertThat(result.avoidanceEventCount()).isEqualTo(120);
        assertThat(result.earliestEventTimestamp()).isEqualTo(1000L);
    }

    @Test
    void corridorRowsExposeRideCountsAndSafetyEvidence() {
        when(streetSegmentRepository.findCorridorRankings(
                anyString(), anyInt(), anyInt(), anyLong(), anyLong(), anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{
                        "Chausseestraße", 134L, 80L, 1491L, 600L, 42L,
                        13.38, 52.52, 13.39, 52.55, 123L, 68L, "101,205,309"
                }));

        List<CorridorRankingDto> result = service.getCorridorRanking(
                SegmentEventType.AVOIDANCE, 5, 8, null, null, null);

        assertThat(result).singleElement().satisfies(corridor -> {
            assertThat(corridor.streetName()).isEqualTo("Chausseestraße");
            assertThat(corridor.avoidanceRideCount()).isEqualTo(134);
            assertThat(corridor.scaryIncidentCount()).isEqualTo(68);
            assertThat(corridor.topSegmentId()).isEqualTo(123L);
            assertThat(corridor.segmentIds()).containsExactly(101L, 205L, 309L);
        });
    }

    @Test
    void infrastructureSignalsCalculateCoverageBaselineAndDifference() {
        Query totalsQuery = fluentQuery();
        Query bucketsQuery = fluentQuery();
        when(entityManager.createNativeQuery(anyString())).thenReturn(totalsQuery, bucketsQuery);
        when(totalsQuery.getSingleResult()).thenReturn(new Object[]{1000L, 400L, 35L, 65L});
        when(bucketsQuery.getResultList()).thenReturn(List.<Object[]>of(
                new Object[]{"sett", 80L, 20L},
                new Object[]{"asphalt", 30L, 70L}
        ));

        InfrastructureSignalsDto result = service.getInfrastructureSignals(
                AnalysisDimension.SURFACE, 20, 10, null, null, null);

        assertThat(result.coverageShare()).isEqualTo(0.4);
        assertThat(result.baselineAvoidanceShare()).isEqualTo(0.35);
        assertThat(result.buckets()).extracting(InfrastructureSignalsDto.InfrastructureSignalBucketDto::value)
                .containsExactly("sett", "asphalt");
        assertThat(result.buckets().getFirst().percentagePointDifference()).isCloseTo(45.0,
                org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void unsupportedInfrastructureDimensionIsRejected() {
        assertThatThrownBy(() -> service.getInfrastructureSignals(
                AnalysisDimension.WEATHER_CODE, 20, 10, null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Infrastructure dimension");
    }

    @Test
    void invertedDateRangeIsRejected() {
        assertThatThrownBy(() -> service.getAnalyticsContext(2000L, 1000L, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("from must be before");
    }

    private Query fluentQuery() {
        Query query = mock(Query.class);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        return query;
    }
}

package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.RouteComparisonReview;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ManualRouteComparisonClassification;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonReviewIssue;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.RouteComparisonReviewRepository;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteReviewDetailDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteReviewRequestDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteReviewSampleDto;
import com.opencsv.CSVReader;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.StringReader;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteComparisonReviewServiceTest {

    private final EntityManager entityManager = mock(EntityManager.class);
    private final RideRepository rideRepository = mock(RideRepository.class);
    private final RouteComparisonReviewRepository reviewRepository =
            mock(RouteComparisonReviewRepository.class);
    private final RouteComparisonReviewService service = new RouteComparisonReviewService(
            entityManager,
            rideRepository,
            reviewRepository,
            new ObjectMapper(),
            30,
            0.10,
            500.0,
            0.30
    );

    @Test
    void sampleIsOrderedAndIncludesExistingReviews() {
        Query query = sampleQuery();
        UUID rideId = UUID.fromString("cc1ad428-a775-4aef-b069-2e4a4ce1833f");
        when(query.getResultList()).thenReturn(List.<Object[]>of(sampleRow(rideId)));

        Ride ride = new Ride();
        ride.setId(rideId);
        RouteComparisonReview review = new RouteComparisonReview();
        review.setRide(ride);
        review.setManualClassification(ManualRouteComparisonClassification.LOCAL_DETOUR);
        review.setIssueCodes(Set.of(RouteComparisonReviewIssue.POOR_GPS_QUALITY));
        review.setReviewedAt(Instant.ofEpochMilli(1_700_000_000_000L));
        when(reviewRepository.findAllByRideIdIn(any())).thenReturn(List.of(review));

        RouteReviewSampleDto result = service.getSample();

        assertThat(result.sampleSizePerType()).isEqualTo(30);
        assertThat(result.totalItems()).isEqualTo(1);
        assertThat(result.reviewedItems()).isEqualTo(1);
        assertThat(result.representativeOfPrevalence()).isFalse();
        assertThat(result.items().getFirst().sampleOrder()).isEqualTo(1);
        assertThat(result.items().getFirst().review().manualClassification())
                .isEqualTo(ManualRouteComparisonClassification.LOCAL_DETOUR);
        verify(query).setParameter("perType", 30);
    }

    @Test
    void detailReturnsBothRoutesAndPreferenceSignals() {
        UUID rideId = UUID.fromString("cc1ad428-a775-4aef-b069-2e4a4ce1833f");
        Query sampleQuery = mock(Query.class);
        Query routeQuery = mock(Query.class);
        Query signalQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString()))
                .thenReturn(sampleQuery, routeQuery, signalQuery);
        when(sampleQuery.setParameter(anyString(), any())).thenReturn(sampleQuery);
        when(routeQuery.setParameter(anyString(), any())).thenReturn(routeQuery);
        when(signalQuery.setParameter(anyString(), any())).thenReturn(signalQuery);
        when(sampleQuery.getResultList()).thenReturn(List.<Object[]>of(sampleRow(rideId)));
        when(reviewRepository.findAllByRideIdIn(any())).thenReturn(List.of());
        when(routeQuery.getSingleResult()).thenReturn(new Object[]{
                1_700_000_060_000L,
                7.5,
                42L,
                "{\"type\":\"LineString\",\"coordinates\":[[13.4,52.5],[13.5,52.6]]}",
                "{\"type\":\"LineString\",\"coordinates\":[[13.4,52.5],[13.45,52.55]]}"
        });
        when(signalQuery.getResultList()).thenReturn(List.<Object[]>of(new Object[]{
                UUID.randomUUID(),
                42L,
                "PREFERENCE",
                "Example street",
                "{\"type\":\"LineString\",\"coordinates\":[[13.4,52.5],[13.41,52.51]]}"
        }));

        RouteReviewDetailDto detail = service.getDetail(rideId);

        assertThat(detail.durationSeconds()).isEqualTo(60);
        assertThat(detail.observedRoute().coordinates()).hasSize(2);
        assertThat(detail.shortestRoute().type()).isEqualTo("LineString");
        assertThat(detail.signals()).singleElement().satisfies(signal -> {
            assertThat(signal.eventType()).isEqualTo("PREFERENCE");
            assertThat(signal.streetName()).isEqualTo("Example street");
        });
        assertThat(detail.detourThresholdRatio()).isEqualTo(0.10);
        assertThat(detail.maximumEquivalentExcessDistanceMeters()).isEqualTo(500.0);
        assertThat(detail.minimumOverlapRatio()).isEqualTo(0.30);
    }

    @Test
    void csvExportIncludesTheClassificationPolicy() throws Exception {
        UUID rideId = UUID.fromString("cc1ad428-a775-4aef-b069-2e4a4ce1833f");
        Query sampleQuery = mock(Query.class);
        Query routeQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(sampleQuery, routeQuery);
        when(sampleQuery.setParameter(anyString(), any())).thenReturn(sampleQuery);
        when(routeQuery.setParameter(anyString(), any())).thenReturn(routeQuery);
        when(sampleQuery.getResultList()).thenReturn(List.<Object[]>of(sampleRow(rideId)));
        when(reviewRepository.findAllByRideIdIn(any())).thenReturn(List.of());
        when(routeQuery.getSingleResult()).thenReturn(new Object[]{
                1_700_000_060_000L,
                7.5,
                42L,
                "{\"type\":\"LineString\",\"coordinates\":[[13.4,52.5],[13.5,52.6]]}",
                "{\"type\":\"LineString\",\"coordinates\":[[13.4,52.5],[13.45,52.55]]}"
        });

        List<String[]> records;
        try (CSVReader reader = new CSVReader(new StringReader(service.exportSampleCsv()))) {
            records = reader.readAll();
        }

        assertThat(records).hasSize(2);
        assertThat(records.getFirst()).containsExactly(
                "ride_id", "sample_order", "class_sample_rank", "automated_classification",
                "start_time", "ride_intent", "bike_type", "actual_distance_m",
                "shortest_path_distance_m", "absolute_excess_distance_m", "relative_detour_ratio",
                "overlap_ratio", "detour_threshold_ratio", "maximum_equivalent_excess_distance_m",
                "minimum_overlap_ratio", "median_gps_accuracy_m", "gps_point_count",
                "observed_route_geojson", "shortest_route_geojson", "manual_classification",
                "issue_codes", "review_notes", "reviewed_at");
        assertThat(records.get(1)[12]).isEqualTo("0.1");
        assertThat(records.get(1)[13]).isEqualTo("500.0");
        assertThat(records.get(1)[14]).isEqualTo("0.3");
    }

    @Test
    void otherIssueRequiresAReviewNote() {
        Query query = sampleQuery();
        UUID rideId = UUID.randomUUID();
        when(query.getResultList()).thenReturn(List.<Object[]>of(sampleRow(rideId)));
        when(reviewRepository.findAllByRideIdIn(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.saveReview(rideId, new RouteReviewRequestDto(
                ManualRouteComparisonClassification.UNCERTAIN,
                Set.of(RouteComparisonReviewIssue.OTHER),
                "  ")))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).contains("OTHER");
                });
    }

    private Query sampleQuery() {
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        return query;
    }

    private Object[] sampleRow(UUID rideId) {
        return new Object[]{
                rideId,
                1_700_000_000_000L,
                "LOCAL_DETOUR",
                "COMMUTE",
                "CITY_TREKKING_BIKE",
                1_200.0,
                1_000.0,
                200.0,
                0.20,
                0.55,
                1L
        };
    }
}

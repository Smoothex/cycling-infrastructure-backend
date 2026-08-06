package berlin.tu.cyclinginfrastructurebackend.service;

import com.opencsv.CSVReader;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.StringReader;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RouteComparisonExportServiceTest {

    private final EntityManager entityManager = mock(EntityManager.class);
    private final RouteComparisonExportService service = new RouteComparisonExportService(entityManager);

    @Test
    void exportContainsMetricsGeometriesAndEmptyReviewColumns() throws Exception {
        Query query = mock(Query.class);
        UUID rideId = UUID.fromString("cc1ad428-a775-4aef-b069-2e4a4ce1833f");
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.<Object[]>of(new Object[]{
                rideId,
                1_700_000_000_000L,
                "LOCAL_DETOUR",
                1_200.0,
                1_000.0,
                200.0,
                0.20,
                0.55,
                7.5,
                42L,
                "LINESTRING (13.4 52.5, 13.5 52.6)",
                "LINESTRING (13.4 52.5, 13.45 52.55)"
        }));

        String csv = service.exportCalibrationSample(1_600_000_000_000L, 1_800_000_000_000L, 50);

        List<String[]> records;
        try (CSVReader reader = new CSVReader(new StringReader(csv))) {
            records = reader.readAll();
        }
        assertThat(records).hasSize(2);
        assertThat(records.getFirst()).containsExactly(
                "ride_id", "start_time", "baseline_route_comparison_type",
                "actual_distance_m", "shortest_path_distance_m", "absolute_excess_distance_m",
                "relative_detour_ratio", "overlap_ratio", "median_gps_accuracy_m",
                "gps_point_count", "actual_route_wkt", "shortest_route_wkt",
                "review_label", "review_notes");
        assertThat(records.get(1)[0]).isEqualTo(rideId.toString());
        assertThat(records.get(1)[10]).contains(",");
        assertThat(records.get(1)[12]).isEmpty();
        assertThat(records.get(1)[13]).isEmpty();
        verify(query).setParameter("fromTime", 1_600_000_000_000L);
        verify(query).setParameter("toTime", 1_800_000_000_000L);
        verify(query).setParameter("perType", 50);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        assertThat(sql.getValue()).contains("PARTITION BY r.route_comparison_type");
    }

    @Test
    void invertedDateRangeIsRejectedBeforeQuerying() {
        assertThatThrownBy(() -> service.exportCalibrationSample(2_000L, 1_000L, 50))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }
}

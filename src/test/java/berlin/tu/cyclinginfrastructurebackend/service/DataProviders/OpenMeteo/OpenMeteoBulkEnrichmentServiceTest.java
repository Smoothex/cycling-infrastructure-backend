package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo;

import berlin.tu.cyclinginfrastructurebackend.repository.OpenMeteoWeatherBatchRepository;
import berlin.tu.cyclinginfrastructurebackend.service.PipelineActivityTracker;
import berlin.tu.cyclinginfrastructurebackend.service.TileBuildService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenMeteoBulkEnrichmentServiceTest {

    private final OpenMeteoWeatherBatchRepository repository = mock(OpenMeteoWeatherBatchRepository.class);
    private final OpenMeteoBulkClient client = mock(OpenMeteoBulkClient.class);
    private final OpenMeteoProperties properties = new OpenMeteoProperties();
    private final TileBuildService tileBuildService = mock(TileBuildService.class);
    private final OpenMeteoBulkEnrichmentService service = new OpenMeteoBulkEnrichmentService(
            repository, client, properties, tileBuildService, new PipelineActivityTracker());

    private final OpenMeteoClaim claimed = new OpenMeteoClaim(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            List.of(new OpenMeteoGridYear(525, 134, 2024)),
            2);

    @BeforeEach
    void pendingBatch() {
        when(repository.hasPendingWork()).thenReturn(true);
        when(repository.claimNextBatch(5, 25_000)).thenReturn(Optional.of(claimed));
    }

    @Test
    void finalizesCachedEventsWithoutCallingTheApi() {
        when(repository.hasCachedWeather(claimed)).thenReturn(true);
        when(repository.applyCachedWeather(claimed)).thenReturn(2);
        when(repository.findMissingLocations(claimed)).thenReturn(List.of());

        OpenMeteoEnrichmentBatchResult result = service.processNextBatch();

        assertThat(result.enrichedEvents()).isEqualTo(2);
        assertThat(result.downloadedRows()).isZero();
        verify(client, never()).fetch(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(tileBuildService).markDataChanged();
    }

    @Test
    void requestsTheUtcDateWindowPersistsRowsAndFinalizesOnlyMatches() {
        long minimum = Instant.parse("2024-02-01T23:30:00Z").toEpochMilli();
        long maximum = Instant.parse("2024-02-03T00:15:00Z").toEpochMilli();
        List<OpenMeteoLocation> locations = List.of(new OpenMeteoLocation(525, 134));
        List<OpenMeteoHourlyRow> rows = List.of(new OpenMeteoHourlyRow(
                525, 134, Instant.parse("2024-02-01T23:00:00Z").toEpochMilli(),
                1.0, 0.0, 2.0, 3.0, 4));
        when(repository.hasCachedWeather(claimed)).thenReturn(false);
        when(repository.applyCachedWeather(claimed)).thenReturn(1);
        when(repository.findMissingLocations(claimed)).thenReturn(locations);
        when(repository.findMissingWindow(claimed)).thenReturn(
                new OpenMeteoWeatherBatchRepository.MissingWindow(minimum, maximum));
        when(client.fetch(locations, LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 3)))
                .thenReturn(rows);
        when(repository.markRemainingEventsError(claimed)).thenReturn(1);

        OpenMeteoEnrichmentBatchResult result = service.processNextBatch();

        InOrder persistenceOrder = inOrder(repository);
        persistenceOrder.verify(repository).upsertHourlyWeather(rows);
        persistenceOrder.verify(repository).applyCachedWeather(claimed);
        assertThat(result.downloadedEvents()).isEqualTo(1);
        assertThat(result.missingEvents()).isEqualTo(1);
        verify(tileBuildService).markDataChanged();
    }

    @Test
    void releasesTheUnresolvedClaimAfterATransientFailure() {
        List<OpenMeteoLocation> locations = List.of(new OpenMeteoLocation(525, 134));
        when(repository.findMissingLocations(claimed)).thenReturn(locations);
        when(repository.findMissingWindow(claimed)).thenReturn(
                new OpenMeteoWeatherBatchRepository.MissingWindow(
                        Instant.parse("2024-01-01T00:00:00Z").toEpochMilli(),
                        Instant.parse("2024-01-01T01:00:00Z").toEpochMilli()));
        when(client.fetch(locations, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)))
                .thenThrow(new OpenMeteoRetryableException("timeout", new RuntimeException()));
        when(repository.releaseBatch(claimed)).thenReturn(2);

        assertThatThrownBy(service::processNextBatch)
                .isInstanceOf(OpenMeteoRetryableException.class);

        verify(repository).releaseBatch(claimed);
        verify(repository, never()).markRemainingEventsError(claimed);
    }

    @Test
    void marksUnresolvedClaimErrorsAfterAnInvalidSuccessfulResponse() {
        List<OpenMeteoLocation> locations = List.of(new OpenMeteoLocation(525, 134));
        when(repository.findMissingLocations(claimed)).thenReturn(locations);
        when(repository.findMissingWindow(claimed)).thenReturn(
                new OpenMeteoWeatherBatchRepository.MissingWindow(
                        Instant.parse("2024-01-01T00:00:00Z").toEpochMilli(),
                        Instant.parse("2024-01-01T01:00:00Z").toEpochMilli()));
        when(client.fetch(locations, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 1)))
                .thenThrow(new OpenMeteoResponseException("misaligned arrays"));
        when(repository.markRemainingEventsError(claimed)).thenReturn(2);

        OpenMeteoEnrichmentBatchResult result = service.processNextBatch();

        assertThat(result.missingEvents()).isEqualTo(2);
        verify(repository).markRemainingEventsError(claimed);
        verify(repository, never()).releaseBatch(claimed);
    }

    @Test
    void releasesTheBoundedClaimAfterADatabaseTimeout() {
        QueryTimeoutException timeout = new QueryTimeoutException(
                "weather update timed out", new RuntimeException("statement timeout"));
        when(repository.hasCachedWeather(claimed)).thenThrow(timeout);
        when(repository.releaseBatch(claimed)).thenReturn(2);

        assertThatThrownBy(service::processNextBatch).isSameAs(timeout);

        verify(repository).releaseBatch(claimed);
        verify(repository, never()).markRemainingEventsError(claimed);
    }
}

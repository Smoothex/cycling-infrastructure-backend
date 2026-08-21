package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo;

import berlin.tu.cyclinginfrastructurebackend.repository.OpenMeteoWeatherBatchRepository;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.ApiRateLimitException;
import berlin.tu.cyclinginfrastructurebackend.service.PipelineActivityTracker;
import berlin.tu.cyclinginfrastructurebackend.service.TileBuildService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Coordinates one restart-safe, bulk Open-Meteo enrichment batch. */
@Service
public class OpenMeteoBulkEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoBulkEnrichmentService.class);

    private final OpenMeteoWeatherBatchRepository batchRepository;
    private final OpenMeteoBulkClient bulkClient;
    private final OpenMeteoProperties properties;
    private final TileBuildService tileBuildService;
    private final PipelineActivityTracker pipelineActivityTracker;

    public OpenMeteoBulkEnrichmentService(OpenMeteoWeatherBatchRepository batchRepository,
                                          OpenMeteoBulkClient bulkClient,
                                          OpenMeteoProperties properties,
                                          TileBuildService tileBuildService,
                                          PipelineActivityTracker pipelineActivityTracker) {
        this.batchRepository = batchRepository;
        this.bulkClient = bulkClient;
        this.properties = properties;
        this.tileBuildService = tileBuildService;
        this.pipelineActivityTracker = pipelineActivityTracker;
    }

    /** Processes at most one configured grid/year batch. */
    public OpenMeteoEnrichmentBatchResult processNextBatch() {
        if (!batchRepository.hasPendingWork()) {
            return OpenMeteoEnrichmentBatchResult.noWork();
        }

        int changedEvents = 0;
        try (PipelineActivityTracker.Activity ignored = pipelineActivityTracker.beginWork()) {
            Instant startedAt = Instant.now();
            int mappedSegments = batchRepository.populateMissingSegmentGrids();
            int invalidEvents = batchRepository.markInvalidPendingEvents();
            Optional<OpenMeteoClaim> optionalClaim = batchRepository.claimNextBatch(
                    properties.getBatchSize(), properties.getEventBatchSize());
            if (optionalClaim.isEmpty()) {
                OpenMeteoEnrichmentBatchResult result = new OpenMeteoEnrichmentBatchResult(
                        mappedSegments, invalidEvents, 0, 0, 0, 0, 0, 0);
                logResult(null, false, result, startedAt);
                return result;
            }

            OpenMeteoClaim claim = optionalClaim.get();
            boolean cacheAvailable = false;
            try {
                cacheAvailable = batchRepository.hasCachedWeather(claim);
                int cachedEvents = cacheAvailable ? batchRepository.applyCachedWeather(claim) : 0;
                changedEvents += cachedEvents;

                List<OpenMeteoLocation> missingLocations = batchRepository.findMissingLocations(claim);
                OpenMeteoWeatherBatchRepository.MissingWindow missingWindow =
                        batchRepository.findMissingWindow(claim);
                if (missingLocations.isEmpty() || missingWindow == null) {
                    OpenMeteoEnrichmentBatchResult result = new OpenMeteoEnrichmentBatchResult(
                            mappedSegments, invalidEvents, claim.gridYears().size(), claim.eventCount(),
                            cachedEvents, 0, 0, 0);
                    logResult(claim, cacheAvailable, result, startedAt);
                    return result;
                }

                LocalDate startDate = utcDate(missingWindow.minimumTimestamp());
                LocalDate endDate = utcDate(missingWindow.maximumTimestamp());
                List<OpenMeteoHourlyRow> downloaded = bulkClient.fetch(
                        missingLocations, startDate, endDate);
                batchRepository.upsertHourlyWeather(downloaded);

                int downloadedEvents = batchRepository.applyCachedWeather(claim);
                changedEvents += downloadedEvents;
                int missingEvents = batchRepository.markRemainingEventsError(claim);
                OpenMeteoEnrichmentBatchResult result = new OpenMeteoEnrichmentBatchResult(
                        mappedSegments, invalidEvents, claim.gridYears().size(), claim.eventCount(),
                        cachedEvents, downloaded.size(), downloadedEvents, missingEvents);
                logResult(claim, cacheAvailable, result, startedAt);
                return result;
            } catch (ApiRateLimitException | OpenMeteoRetryableException exception) {
                int released = batchRepository.releaseBatch(claim);
                log.warn("Open-Meteo batch {} failed transiently; released {} events to PENDING: {}",
                        claim.batchId(), released, exception.getMessage());
                throw exception;
            } catch (OpenMeteoResponseException exception) {
                int errors = batchRepository.markRemainingEventsError(claim);
                log.error("Open-Meteo batch {} response is not usable; marked {} events ERROR: {}",
                        claim.batchId(), errors, exception.getMessage());
                return new OpenMeteoEnrichmentBatchResult(
                        mappedSegments, invalidEvents, claim.gridYears().size(), claim.eventCount(),
                        changedEvents, 0, 0, errors);
            } catch (QueryTimeoutException exception) {
                int released = batchRepository.releaseBatch(claim);
                log.warn("Open-Meteo batch {} database operation timed out; released {} events to PENDING: {}",
                        claim.batchId(), released, rootCauseMessage(exception));
                throw exception;
            } catch (RuntimeException exception) {
                int released = batchRepository.releaseBatch(claim);
                log.error("Open-Meteo batch {} failed unexpectedly; released {} events to PENDING: {}",
                        claim.batchId(), released, exception.getMessage(), exception);
                throw exception;
            }
        } finally {
            if (changedEvents > 0) {
                tileBuildService.markDataChanged();
            }
        }
    }

    private LocalDate utcDate(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate();
    }

    private void logResult(OpenMeteoClaim claim,
                           boolean cacheAvailable,
                           OpenMeteoEnrichmentBatchResult result,
                           Instant startedAt) {
        log.info("Open-Meteo bulk batch: batchId={}, locations={}, events={}, cacheAvailable={}, cached={}, "
                        + "downloadedRows={}, downloadedMatches={}, missing={}, invalid={}, newGridMappings={}, "
                        + "elapsed={}",
                claim == null ? "none" : claim.batchId(),
                result.claimedLocations(), result.claimedEvents(), cacheAvailable, result.cachedEvents(),
                result.downloadedRows(), result.downloadedEvents(), result.missingEvents(),
                result.invalidEvents(), result.mappedSegments(), Duration.between(startedAt, Instant.now()));
    }

    private String rootCauseMessage(RuntimeException exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}

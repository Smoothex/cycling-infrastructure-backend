package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo;

import berlin.tu.cyclinginfrastructurebackend.repository.OpenMeteoWeatherBatchRepository;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.ApiRateLimitException;
import berlin.tu.cyclinginfrastructurebackend.service.PipelineActivityTracker;
import berlin.tu.cyclinginfrastructurebackend.service.TileBuildService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

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
            int mappedSegments = batchRepository.populateMissingSegmentGrids();
            int invalidEvents = batchRepository.markInvalidPendingEvents();
            List<OpenMeteoGridYear> claimed = batchRepository.claimNextBatch(properties.getBatchSize());
            if (claimed.isEmpty()) {
                OpenMeteoEnrichmentBatchResult result = new OpenMeteoEnrichmentBatchResult(
                        mappedSegments, invalidEvents, 0, 0, 0, 0, 0, 0);
                logResult(result);
                return result;
            }

            int claimedEvents = batchRepository.countProcessingEvents(claimed);
            try {
                int cachedEvents = batchRepository.applyCachedWeather(claimed);
                changedEvents += cachedEvents;

                List<OpenMeteoLocation> missingLocations = batchRepository.findMissingLocations(claimed);
                OpenMeteoWeatherBatchRepository.MissingWindow missingWindow =
                        batchRepository.findMissingWindow(claimed);
                if (missingLocations.isEmpty() || missingWindow == null) {
                    OpenMeteoEnrichmentBatchResult result = new OpenMeteoEnrichmentBatchResult(
                            mappedSegments, invalidEvents, claimed.size(), claimedEvents,
                            cachedEvents, 0, 0, 0);
                    logResult(result);
                    return result;
                }

                LocalDate startDate = utcDate(missingWindow.minimumTimestamp());
                LocalDate endDate = utcDate(missingWindow.maximumTimestamp());
                List<OpenMeteoHourlyRow> downloaded = bulkClient.fetch(
                        missingLocations, startDate, endDate);
                batchRepository.upsertHourlyWeather(downloaded);

                int downloadedEvents = batchRepository.applyCachedWeather(claimed);
                changedEvents += downloadedEvents;
                int missingEvents = batchRepository.markRemainingEventsError(claimed);
                OpenMeteoEnrichmentBatchResult result = new OpenMeteoEnrichmentBatchResult(
                        mappedSegments, invalidEvents, claimed.size(), claimedEvents,
                        cachedEvents, downloaded.size(), downloadedEvents, missingEvents);
                logResult(result);
                return result;
            } catch (ApiRateLimitException | OpenMeteoRetryableException exception) {
                int released = batchRepository.releaseBatch(claimed);
                log.warn("Open-Meteo bulk request failed transiently; released {} events to PENDING: {}",
                        released, exception.getMessage());
                throw exception;
            } catch (OpenMeteoResponseException exception) {
                int errors = batchRepository.markRemainingEventsError(claimed);
                log.error("Open-Meteo bulk response is not usable; marked {} events ERROR: {}",
                        errors, exception.getMessage());
                return new OpenMeteoEnrichmentBatchResult(
                        mappedSegments, invalidEvents, claimed.size(), claimedEvents,
                        changedEvents, 0, 0, errors);
            } catch (RuntimeException exception) {
                int released = batchRepository.releaseBatch(claimed);
                log.error("Open-Meteo bulk enrichment failed unexpectedly; released {} events to PENDING: {}",
                        released, exception.getMessage(), exception);
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

    private void logResult(OpenMeteoEnrichmentBatchResult result) {
        log.info("Open-Meteo bulk batch: locations={}, events={}, cached={}, downloadedRows={}, "
                        + "downloadedMatches={}, missing={}, invalid={}, newGridMappings={}",
                result.claimedLocations(), result.claimedEvents(), result.cachedEvents(),
                result.downloadedRows(), result.downloadedEvents(), result.missingEvents(),
                result.invalidEvents(), result.mappedSegments());
    }
}

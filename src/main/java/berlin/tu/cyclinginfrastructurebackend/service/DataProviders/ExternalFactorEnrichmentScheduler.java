package berlin.tu.cyclinginfrastructurebackend.service.DataProviders;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.EnrichmentStatus;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentEventRepository;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.VIZ.RoadClosures.RoadClosureDataProvider;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.VIZ.Traffic.TrafficDataProvider;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo.OpenMeteoBulkEnrichmentService;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo.OpenMeteoProperties;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo.OpenMeteoRetryableException;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeV2EnrichmentService;
import berlin.tu.cyclinginfrastructurebackend.service.PipelineWorkClaimService;
import berlin.tu.cyclinginfrastructurebackend.service.PipelineActivityTracker;
import berlin.tu.cyclinginfrastructurebackend.service.TileBuildService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Component
public class ExternalFactorEnrichmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExternalFactorEnrichmentScheduler.class);
    private static final String WEATHER_LABEL = "Weather (Open Meteo API)";
    private static final Duration RATE_LIMIT_INITIAL_BACKOFF = Duration.ofMinutes(1);
    private static final Duration RATE_LIMIT_MAX_BACKOFF = Duration.ofMinutes(30);

    /** Per-pipeline pause deadline after an API rate limit; batches are skipped until it passes. */
    private final Map<String, Instant> rateLimitPauseUntil = new ConcurrentHashMap<>();
    /** Consecutive rate-limit hits per pipeline, drives exponential backoff when the API gives no reset time. */
    private final Map<String, Integer> consecutiveRateLimits = new ConcurrentHashMap<>();

    private final SegmentEventRepository segmentEventRepository;
    private final OpenMeteoBulkEnrichmentService weatherEnrichmentService;
    private final OpenMeteoProperties openMeteoProperties;
    private final RoadClosureDataProvider roadClosureDataProvider;
    private final OhsomeV2EnrichmentService ohsomeV2EnrichmentService;
    private final TrafficDataProvider trafficDataProvider;
    private final PipelineWorkClaimService workClaimService;
    private final TileBuildService tileBuildService;
    private final PipelineActivityTracker pipelineActivityTracker;

    @Value("${pipeline.enabled:true}")
    private boolean pipelineEnabled;

    @Value("${pipeline.enrichment.enabled:true}")
    private boolean enrichmentEnabled;

    @Value("${pipeline.enrichment.weather.enabled:false}")
    private boolean weatherEnabled;

    @Value("${pipeline.enrichment.berlin-open-data.enabled:false}")
    private boolean berlinOpenDataEnabled;

    @Value("${pipeline.enrichment.berlin-open-data.batch-size:100}")
    private int berlinOpenDataBatchSize;

    @Value("${pipeline.enrichment.ohsome.enabled:false}")
    private boolean ohsomeEnabled;

    @Value("${pipeline.enrichment.ohsome.batch-size:5000}")
    private int ohsomeBatchSize;

    @Value("${pipeline.enrichment.traffic.enabled:false}")
    private boolean trafficEnabled;

    @Value("${pipeline.enrichment.traffic.batch-size:500}")
    private int trafficBatchSize;

    public ExternalFactorEnrichmentScheduler(SegmentEventRepository segmentEventRepository,
                                             OpenMeteoBulkEnrichmentService weatherEnrichmentService,
                                             OpenMeteoProperties openMeteoProperties,
                                             RoadClosureDataProvider roadClosureDataProvider,
                                             OhsomeV2EnrichmentService ohsomeV2EnrichmentService,
                                             TrafficDataProvider trafficDataProvider,
                                             PipelineWorkClaimService workClaimService,
                                             TileBuildService tileBuildService,
                                             PipelineActivityTracker pipelineActivityTracker) {
        this.segmentEventRepository = segmentEventRepository;
        this.weatherEnrichmentService = weatherEnrichmentService;
        this.openMeteoProperties = openMeteoProperties;
        this.roadClosureDataProvider = roadClosureDataProvider;
        this.ohsomeV2EnrichmentService = ohsomeV2EnrichmentService;
        this.trafficDataProvider = trafficDataProvider;
        this.workClaimService = workClaimService;
        this.tileBuildService = tileBuildService;
        this.pipelineActivityTracker = pipelineActivityTracker;
    }

    @Scheduled(fixedDelayString = "${pipeline.enrichment.weather.delay-ms:60000}")
    public void enrichWeatherPending() {
        if (!isEnabled(weatherEnabled)) return;

        Instant pausedUntil = rateLimitPauseUntil.get(WEATHER_LABEL);
        if (pausedUntil != null && Instant.now().isBefore(pausedUntil)) {
            log.debug("{} enrichment paused until {} after a transient API failure.",
                    WEATHER_LABEL, pausedUntil);
            return;
        }

        try {
            weatherEnrichmentService.processNextBatch();
            consecutiveRateLimits.remove(WEATHER_LABEL);
            rateLimitPauseUntil.remove(WEATHER_LABEL);
        } catch (ApiRateLimitException exception) {
            Instant resumeAt = pauseAfterRateLimit(WEATHER_LABEL, exception.getRetryAt());
            log.warn("{} enrichment rate limited; paused until {}.", WEATHER_LABEL, resumeAt);
        } catch (OpenMeteoRetryableException exception) {
            Instant resumeAt = pauseAfterRateLimit(WEATHER_LABEL, null);
            log.warn("{} enrichment failed transiently; paused until {}: {}",
                    WEATHER_LABEL, resumeAt, exception.getMessage());
        } catch (RuntimeException exception) {
            log.error("{} enrichment batch failed: {}", WEATHER_LABEL, exception.getMessage(), exception);
        }
    }

    @Scheduled(fixedDelayString = "${pipeline.enrichment.berlin-open-data.delay-ms:60000}")
    public void enrichBerlinOpenDataPending() {
        if (!isEnabled(berlinOpenDataEnabled)) return;

        runClaimedBatch(
                "VIZ Berlin - Road disruption",
                () -> workClaimService.claimBerlinOpenDataEvents(berlinOpenDataBatchSize),
                event -> {
                    StreetSegment segment = event.getSegment();
                    roadClosureDataProvider.enrichSegment(
                            segment,
                            event.getEventTimestamp(),
                            event.getEventTimestamp()
                    );
                    segmentEventRepository.markBerlinOpenDataEnriched(event.getId(), EnrichmentStatus.DONE);
                },
                segmentEventRepository::updateBerlinOpenDataProcessingStatus,
                0
        );
    }

    @Scheduled(fixedDelayString = "${pipeline.enrichment.ohsome.delay-ms:60000}")
    public void enrichOhsomePending() {
        if (!isEnabled(ohsomeEnabled)) return;
        ohsomeV2EnrichmentService.drainPending(ohsomeBatchSize);
    }

    @Scheduled(fixedDelayString = "${pipeline.enrichment.traffic.delay-ms:60000}")
    public void enrichTrafficPending() {
        if (!isEnabled(trafficEnabled)) return;

        runClaimedBatch(
                "VIZ Berlin - Traffic",
                () -> workClaimService.claimTrafficEvents(trafficBatchSize),
                event -> {
                    trafficDataProvider.enrichEvent(event);
                    segmentEventRepository.markTrafficEnriched(
                            event.getId(),
                            EnrichmentStatus.DONE,
                            event.getTrafficVolumeKfz(),
                            event.getTrafficSpeedKfz(),
                            event.getTrafficVolumePkw(),
                            event.getTrafficSpeedPkw(),
                            event.getTrafficVolumeLkw(),
                            event.getTrafficSpeedLkw(),
                            event.getTrafficSourceType(),
                            event.getTrafficCondition(),
                            event.getTrafficEnrichmentStatus()
                    );
                },
                segmentEventRepository::updateTrafficProcessingStatus,
                0
        );
    }

    private boolean isEnabled(boolean providerEnabled) {
        return pipelineEnabled && enrichmentEnabled && providerEnabled;
    }

    private void runClaimedBatch(String label,
                                 Supplier<List<UUID>> claimFn,
                                 Consumer<SegmentEvent> enrichAndMarkDone,
                                 BiConsumer<UUID, EnrichmentStatus> updateStatus,
                                 long delayBetweenEventsMs) {
        Instant pausedUntil = rateLimitPauseUntil.get(label);
        if (pausedUntil != null && Instant.now().isBefore(pausedUntil)) {
            log.debug("{} enrichment paused until {} after API rate limiting.", label, pausedUntil);
            return;
        }

        List<UUID> eventIds = claimFn.get();
        if (eventIds.isEmpty()) {
            log.debug("No {} events claimed for enrichment.", label);
            return;
        }

        try (PipelineActivityTracker.Activity ignored = pipelineActivityTracker.beginWork()) {
            Instant startedAt = Instant.now();
            Map<UUID, SegmentEvent> eventsById = segmentEventRepository.findWithSegmentByIdIn(eventIds)
                    .stream()
                    .collect(Collectors.toMap(SegmentEvent::getId, event -> event));

            int processed = 0;
            int errors = 0;
            log.debug("{} enrichment batch started. {} claimed events.", label, eventIds.size());

            try {
                for (int i = 0; i < eventIds.size(); i++) {
                    UUID eventId = eventIds.get(i);
                    SegmentEvent event = eventsById.get(eventId);
                    if (event == null) {
                        updateStatus.accept(eventId, EnrichmentStatus.ERROR);
                        errors++;
                        continue;
                    }

                    try {
                        enrichAndMarkDone.accept(event);
                        processed++;

                        if (delayBetweenEventsMs > 0) {
                            Thread.sleep(delayBetweenEventsMs);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        updateStatus.accept(eventId, EnrichmentStatus.ERROR);
                        log.warn("{} enrichment interrupted.", label);
                        return;
                    } catch (ApiRateLimitException e) {
                        Instant resumeAt = pauseAfterRateLimit(label, e.getRetryAt());
                        List<UUID> unprocessed = eventIds.subList(i, eventIds.size());
                        unprocessed.forEach(id -> updateStatus.accept(id, EnrichmentStatus.PENDING));
                        log.warn("{} enrichment hit an API rate limit after {} events; released {} claimed "
                                        + "events back to PENDING and paused until {}.",
                                label, processed, unprocessed.size(), resumeAt);
                        return;
                    } catch (Exception e) {
                        updateStatus.accept(eventId, EnrichmentStatus.ERROR);
                        log.error("Failed to enrich event {} ({}): {}", eventId, label, e.getMessage());
                        errors++;
                    }
                }

                consecutiveRateLimits.remove(label);
                Duration elapsed = Duration.between(startedAt, Instant.now());
                log.info("=== {} enrichment batch complete. {} processed, {} errors in {}s ===",
                        label, processed, errors, elapsed.toSeconds());
            } finally {
                if (processed > 0) {
                    tileBuildService.markDataChanged();
                }
            }
        }
    }

    /**
     * Records the pause deadline for a pipeline that just hit an API rate limit.
     * Uses the reset time communicated by the API when available; otherwise falls
     * back to exponential backoff across consecutive rate-limited batches.
     *
     * @param label the pipeline label used as backoff key
     * @param apiSuppliedRetryAt reset time reported by the API, or null
     * @return the instant until which the pipeline is paused
     */
    private Instant pauseAfterRateLimit(String label, Instant apiSuppliedRetryAt) {
        Instant resumeAt;
        if (apiSuppliedRetryAt != null) {
            resumeAt = apiSuppliedRetryAt;
        } else {
            int attempt = consecutiveRateLimits.merge(label, 1, Integer::sum);
            long multiplier = 1L << Math.min(attempt - 1, 30);
            Duration initialBackoff = WEATHER_LABEL.equals(label)
                    ? openMeteoProperties.getInitialRetryDelay()
                    : RATE_LIMIT_INITIAL_BACKOFF;
            Duration maximumBackoff = WEATHER_LABEL.equals(label)
                    ? openMeteoProperties.getMaxRetryDelay()
                    : RATE_LIMIT_MAX_BACKOFF;
            Duration backoff = initialBackoff.multipliedBy(multiplier);
            if (backoff.compareTo(maximumBackoff) > 0) {
                backoff = maximumBackoff;
            }
            resumeAt = Instant.now().plus(backoff);
        }
        rateLimitPauseUntil.put(label, resumeAt);
        return resumeAt;
    }
}

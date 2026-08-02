package berlin.tu.cyclinginfrastructurebackend.service.DataProviders;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.EnrichmentStatus;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentEventRepository;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.VIZ.RoadClosures.RoadClosureDataProvider;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.VIZ.Traffic.TrafficDataProvider;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo.WeatherDataProvider;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeApiDataProvider;
import berlin.tu.cyclinginfrastructurebackend.service.PipelineWorkClaimService;
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
    private static final long ONE_HOUR_MILLIS = 3_600_000L;
    private static final Duration RATE_LIMIT_INITIAL_BACKOFF = Duration.ofMinutes(1);
    private static final Duration RATE_LIMIT_MAX_BACKOFF = Duration.ofMinutes(30);

    /** Per-pipeline pause deadline after an API rate limit; batches are skipped until it passes. */
    private final Map<String, Instant> rateLimitPauseUntil = new ConcurrentHashMap<>();
    /** Consecutive rate-limit hits per pipeline, drives exponential backoff when the API gives no reset time. */
    private final Map<String, Integer> consecutiveRateLimits = new ConcurrentHashMap<>();

    private final SegmentEventRepository segmentEventRepository;
    private final WeatherDataProvider weatherDataProvider;
    private final RoadClosureDataProvider roadClosureDataProvider;
    private final OhsomeApiDataProvider ohsomeApiDataProvider;
    private final TrafficDataProvider trafficDataProvider;
    private final PipelineWorkClaimService workClaimService;
    private final TileBuildService tileBuildService;

    @Value("${pipeline.enabled:true}")
    private boolean pipelineEnabled;

    @Value("${pipeline.enrichment.enabled:true}")
    private boolean enrichmentEnabled;

    @Value("${pipeline.enrichment.weather.enabled:false}")
    private boolean weatherEnabled;

    @Value("${pipeline.enrichment.weather.batch-size:100}")
    private int weatherBatchSize;

    @Value("${pipeline.enrichment.weather.delay-between-calls-ms:150}")
    private long weatherCallDelayMs;

    @Value("${pipeline.enrichment.berlin-open-data.enabled:false}")
    private boolean berlinOpenDataEnabled;

    @Value("${pipeline.enrichment.berlin-open-data.batch-size:100}")
    private int berlinOpenDataBatchSize;

    @Value("${pipeline.enrichment.ohsome.enabled:false}")
    private boolean ohsomeEnabled;

    @Value("${pipeline.enrichment.ohsome.batch-size:50}")
    private int ohsomeBatchSize;

    @Value("${pipeline.enrichment.ohsome.delay-between-calls-ms:500}")
    private long ohsomeCallDelayMs;

    @Value("${pipeline.enrichment.traffic.enabled:false}")
    private boolean trafficEnabled;

    @Value("${pipeline.enrichment.traffic.batch-size:500}")
    private int trafficBatchSize;

    public ExternalFactorEnrichmentScheduler(SegmentEventRepository segmentEventRepository,
                                             WeatherDataProvider weatherDataProvider,
                                             RoadClosureDataProvider roadClosureDataProvider,
                                             OhsomeApiDataProvider ohsomeApiDataProvider,
                                             TrafficDataProvider trafficDataProvider,
                                             PipelineWorkClaimService workClaimService,
                                             TileBuildService tileBuildService) {
        this.segmentEventRepository = segmentEventRepository;
        this.weatherDataProvider = weatherDataProvider;
        this.roadClosureDataProvider = roadClosureDataProvider;
        this.ohsomeApiDataProvider = ohsomeApiDataProvider;
        this.trafficDataProvider = trafficDataProvider;
        this.workClaimService = workClaimService;
        this.tileBuildService = tileBuildService;
    }

    @Scheduled(fixedDelayString = "${pipeline.enrichment.weather.delay-ms:60000}")
    public void enrichWeatherPending() {
        if (!isEnabled(weatherEnabled)) return;

        runClaimedBatch(
                "Weather (Open Meteo API)",
                () -> workClaimService.claimWeatherEvents(weatherBatchSize),
                event -> {
                    weatherDataProvider.enrichEvent(event);
                    segmentEventRepository.markWeatherEnriched(
                            event.getId(),
                            EnrichmentStatus.DONE,
                            event.getTemperature2m(),
                            event.getPrecipitation(),
                            event.getWindSpeed10m(),
                            event.getWindDirection10m(),
                            event.getWeatherCode(),
                            event.getRelativeWindAngleDegrees(),
                            event.getWindExposure()
                    );
                },
                segmentEventRepository::updateWeatherProcessingStatus,
                weatherCallDelayMs
        );
    }

    @Scheduled(fixedDelayString = "${pipeline.enrichment.berlin-open-data.delay-ms:60000}")
    public void enrichBerlinOpenDataPending() {
        if (!isEnabled(berlinOpenDataEnabled)) return;

        runClaimedBatch(
                "VIZ Berlin - Road disruption",
                () -> workClaimService.claimBerlinOpenDataEvents(berlinOpenDataBatchSize),
                event -> {
                    StreetSegment segment = event.getSegment();
                    long hourStart = event.getEventTimestamp() - (event.getEventTimestamp() % ONE_HOUR_MILLIS);
                    roadClosureDataProvider.enrichSegment(segment, hourStart, hourStart + ONE_HOUR_MILLIS);
                    segmentEventRepository.markBerlinOpenDataEnriched(event.getId(), EnrichmentStatus.DONE);
                },
                segmentEventRepository::updateBerlinOpenDataProcessingStatus,
                0
        );
    }

    @Scheduled(fixedDelayString = "${pipeline.enrichment.ohsome.delay-ms:60000}")
    public void enrichOhsomePending() {
        if (!isEnabled(ohsomeEnabled)) return;

        runClaimedBatch(
                "OSM Infrastructure (ohsome API)",
                () -> workClaimService.claimOhsomeEvents(ohsomeBatchSize),
                event -> {
                    ohsomeApiDataProvider.enrichEvent(event);
                    segmentEventRepository.markOhsomeEnriched(
                            event.getId(),
                            EnrichmentStatus.DONE,
                            event.getSurface(),
                            event.getSmoothness(),
                            event.getLit(),
                            event.getHighway(),
                            event.getCyclewayType(),
                            event.getCyclewayLocation(),
                            event.getCyclewaySurface(),
                            event.getCyclewayWidth(),
                            event.getBicycleOneway()
                    );
                },
                segmentEventRepository::updateOhsomeProcessingStatus,
                ohsomeCallDelayMs
        );
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
            Duration backoff = RATE_LIMIT_INITIAL_BACKOFF.multipliedBy(multiplier);
            if (backoff.compareTo(RATE_LIMIT_MAX_BACKOFF) > 0) {
                backoff = RATE_LIMIT_MAX_BACKOFF;
            }
            resumeAt = Instant.now().plus(backoff);
        }
        rateLimitPauseUntil.put(label, resumeAt);
        return resumeAt;
    }
}

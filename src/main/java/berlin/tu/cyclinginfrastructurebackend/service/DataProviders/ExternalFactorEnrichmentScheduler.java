package berlin.tu.cyclinginfrastructurebackend.service.DataProviders;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentAvoidance;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentAvoidanceRepository;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.BerlinOpenData.RoadClosureDataProvider;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo.WeatherDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;


@Component
public class ExternalFactorEnrichmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExternalFactorEnrichmentScheduler.class);
    private static final long ONE_HOUR_MILLIS = 3_600_000L;

    private final SegmentAvoidanceRepository avoidanceRepository;
    private final WeatherDataProvider weatherDataProvider;
    private final RoadClosureDataProvider roadClosureDataProvider;

    @Value("${enrichment.weather.enabled:false}")
    private boolean weatherEnabled;

    @Value("${enrichment.weather.batch-size:100}")
    private int weatherBatchSize;

    @Value("${enrichment.weather.delay-between-calls-ms:150}")
    private long weatherDelayMs;

    @Value("${enrichment.berlin-open-data.enabled:false}")
    private boolean berlinOpenDataEnabled;

    @Value("${enrichment.berlin-open-data.batch-size:100}")
    private int berlinOpenDataBatchSize;

    public ExternalFactorEnrichmentScheduler(SegmentAvoidanceRepository avoidanceRepository,
                                             WeatherDataProvider weatherDataProvider,
                                             RoadClosureDataProvider roadClosureDataProvider) {
        this.avoidanceRepository = avoidanceRepository;
        this.weatherDataProvider = weatherDataProvider;
        this.roadClosureDataProvider = roadClosureDataProvider;
    }

    // ---- Weather enrichment ----

    @Scheduled(fixedDelayString = "${enrichment.weather.schedule-delay-ms:60000}")
    public void enrichWeatherPending() {
        if (!weatherEnabled) return;

        runEnrichment(
                "Weather",
                avoidanceRepository::findUnenrichedByWeather,
                avoidanceRepository.countByWeatherEnriched(false),
                weatherBatchSize,
                weatherDelayMs,
                weatherDataProvider::enrichSegment,
                avoidance -> {
                    avoidance.setWeatherEnriched(true);
                    avoidanceRepository.save(avoidance);
                }
        );
    }

    // ---- Berlin Open Data enrichment ----

    @Scheduled()
    public void enrichBerlinOpenDataPending() {
        if (!berlinOpenDataEnabled) return;

        runEnrichment(
                "Berlin Open Data",
                avoidanceRepository::findUnenrichedByBerlinOpenData,
                avoidanceRepository.countByBerlinOpenDataEnriched(false),
                berlinOpenDataBatchSize,
                0,
                roadClosureDataProvider::enrichSegment,
                avoidance -> {
                    avoidance.setBerlinOpenDataEnriched(true);
                    avoidanceRepository.save(avoidance);
                }
        );
    }

    // ---- Shared batch-processing logic ----

    /**
     * Processes avoidances in batches, calling the provider
     * for each one and marking it as enriched on success.
     *
     * @param label          human-readable name for logging
     * @param batchFetcher   function that returns the next batch of unenriched avoidances
     * @param totalUnenriched count of remaining avoidances
     * @param batchSize      how many avoidances to fetch per batch
     * @param delayMs        pause between individual calls
     * @param enrichFn       the provider's enrichSegment method reference
     * @param markDone       callback to mark the avoidance as enriched and persist
     */
    private void runEnrichment(String label,
                               Function<Pageable, List<SegmentAvoidance>> batchFetcher,
                               long totalUnenriched,
                               int batchSize,
                               long delayMs,
                               TriConsumer<StreetSegment, Long, Long> enrichFn,
                               Consumer<SegmentAvoidance> markDone) {
        if (totalUnenriched == 0) {
            log.debug("No unenriched avoidances for {}.", label);
            return;
        }

        log.info("=== {} enrichment started. {} unenriched avoidances. ===", label, totalUnenriched);
        Instant runStart = Instant.now();
        int totalProcessed = 0;
        int totalErrors = 0;

        while (true) {
            List<SegmentAvoidance> batch = batchFetcher.apply(PageRequest.of(0, batchSize));
            if (batch.isEmpty()) break;

            for (SegmentAvoidance avoidance : batch) {
                try {
                    StreetSegment segment = avoidance.getSegment();
                    Long avoidedAt = avoidance.getAvoidedAt();

                    long hourStart = avoidedAt - (avoidedAt % ONE_HOUR_MILLIS);
                    enrichFn.accept(segment, hourStart, hourStart + ONE_HOUR_MILLIS);

                    markDone.accept(avoidance);
                    totalProcessed++;

                    if (delayMs > 0) {
                        Thread.sleep(delayMs);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("{} enrichment interrupted.", label);
                    return;
                } catch (Exception e) {
                    log.error("Failed to enrich avoidance {} ({}): {}", avoidance.getId(), label, e.getMessage());
                    totalErrors++;
                }
            }

            log.info("{} enrichment batch done: {} processed, {} errors so far.",
                    label, totalProcessed, totalErrors);
        }

        Duration elapsed = Duration.between(runStart, Instant.now());
        log.info("=== {} enrichment complete. {} processed, {} errors in {}s ===",
                label, totalProcessed, totalErrors, elapsed.toSeconds());
    }

    /** Functional interface for enrichSegment(StreetSegment, Long, Long). */
    @FunctionalInterface
    private interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }
}


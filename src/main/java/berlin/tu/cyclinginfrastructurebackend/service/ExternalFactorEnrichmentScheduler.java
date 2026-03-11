package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentAvoidance;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentAvoidanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Periodically processes unenriched {@link SegmentAvoidance} records by fetching
 * weather data for each avoidance event.
 * <p>
 * Has a configurable delay between API calls to avoid hitting rate limits.
 */
@Component
public class ExternalFactorEnrichmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExternalFactorEnrichmentScheduler.class);
    private static final long ONE_HOUR_MILLIS = 3_600_000L;

    private final SegmentAvoidanceRepository avoidanceRepository;
    private final WeatherDataProvider weatherDataProvider;

    @Value("${enrichment.weather.enabled}")
    private boolean isWeatherEnrichmentEnabled;

    @Value("${enrichment.weather.batch-size:100}")
    private int batchSize;

    @Value("${enrichment.weather.delay-between-calls-ms:150}")
    private long delayBetweenCallsMs;

    public ExternalFactorEnrichmentScheduler(SegmentAvoidanceRepository avoidanceRepository,
                                             WeatherDataProvider weatherDataProvider) {
        this.avoidanceRepository = avoidanceRepository;
        this.weatherDataProvider = weatherDataProvider;
    }

    @Scheduled(fixedDelayString = "${enrichment.weather.schedule-delay-ms:60000}")
    public void enrichPendingAvoidances() {
        if (!isWeatherEnrichmentEnabled) {
            return;
        }

        long unenriched = avoidanceRepository.countByWeatherEnriched(false);
        if (unenriched == 0) {
            log.debug("No unenriched avoidances found.");
            return;
        }

        log.info("=== Weather enrichment started. {} unenriched avoidances. ===", unenriched);
        Instant runStart = Instant.now();
        int totalProcessed = 0;
        int totalErrors = 0;

        while (true) {
            List<SegmentAvoidance> batch = avoidanceRepository.findUnenrichedByWeather(
                    PageRequest.of(0, batchSize));

            if (batch.isEmpty()) break;

            for (SegmentAvoidance avoidance : batch) {
                try {
                    StreetSegment segment = avoidance.getSegment();
                    Long avoidedAt = avoidance.getAvoidedAt();

                    // Fetch weather for the hour surrounding the avoidance event
                    long hourStart = avoidedAt - (avoidedAt % ONE_HOUR_MILLIS);
                    weatherDataProvider.enrichSegment(segment, hourStart, hourStart + ONE_HOUR_MILLIS);
                    // TODO: use enrichSegment from ExternalFactorService to iterate over all providers, not just weather

                    avoidance.setWeatherEnriched(true);
                    avoidanceRepository.save(avoidance);
                    totalProcessed++;

                    // Rate-limit: pause between API calls
                    if (delayBetweenCallsMs > 0) {
                        Thread.sleep(delayBetweenCallsMs);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Weather enrichment interrupted.");
                    return;
                } catch (Exception e) {
                    log.error("Failed to enrich avoidance {}: {}", avoidance.getId(), e.getMessage());
                    // Mark as enriched to avoid retry loops on persistent failures
                    //avoidance.setWeatherEnriched(true);
                    // avoidanceRepository.save(avoidance);
                    totalErrors++;
                }
            }

            log.info("Weather enrichment batch done: {} processed, {} errors so far.",
                    totalProcessed, totalErrors);
        }

        Duration elapsed = Duration.between(runStart, Instant.now());
        log.info("=== Weather enrichment complete. {} processed, {} errors in {}s ===",
                totalProcessed, totalErrors, elapsed.toSeconds());
    }
}


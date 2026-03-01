package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DetourAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(DetourAnalysisScheduler.class);

    private final RideRepository rideRepository;
    private final DetourAnalysisService detourAnalysisService;

    @Value("${analysis.batch.enabled:false}")
    private boolean isBatchEnabled;

    public DetourAnalysisScheduler(RideRepository rideRepository,
                                   DetourAnalysisService detourAnalysisService) {
        this.rideRepository = rideRepository;
        this.detourAnalysisService = detourAnalysisService;
    }

    @Scheduled(fixedDelayString = "${analysis.batch.delay.ms}")
    public void processPendingRides() {
        if (!isBatchEnabled) {
            return;
        }

        List<Ride> pendingBatch = rideRepository.findTop50ByStatus(Status.PENDING);

        if (pendingBatch.isEmpty()) {
            log.debug("No PENDING rides found for analysis.");
            return;
        }

        log.info("Starting analysis batch for {} PENDING rides...", pendingBatch.size());

        int successCount = 0;
        int errorCount = 0;

        for (Ride ride : pendingBatch) {
            try {
                Status finalStatus = detourAnalysisService.analyzeRide(ride.getId());

                if (finalStatus == Status.PROCESSED) {
                    successCount++;
                } else {
                    errorCount++;
                }
            } catch (Exception e) {
                log.error("Fatal error processing ride ID: {}", ride.getId(), e);
                errorCount++;
            }
        }

        log.info("Batch finished. Processed: {}, Skipped/Errors: {}", successCount, errorCount);
    }
}
package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.EnrichmentStatus;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PipelineWorkClaimService {

    private static final Logger log = LoggerFactory.getLogger(PipelineWorkClaimService.class);

    private final EntityManager entityManager;

    public PipelineWorkClaimService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void resetInterruptedWork() {
        int ridesReset = entityManager.createQuery("""
                        UPDATE Ride r
                        SET r.status = :pending
                        WHERE r.status = :analyzing
                        """)
                .setParameter("pending", Status.PENDING)
                .setParameter("analyzing", Status.ANALYZING)
                .executeUpdate();

        int weatherInitialized = initializeEnrichmentStatus("weather_processing_status", "weather_enriched");
        int berlinOpenDataInitialized = initializeEnrichmentStatus(
                "berlin_open_data_processing_status",
                "berlin_open_data_enriched"
        );
        int ohsomeInitialized = initializeEnrichmentStatus("ohsome_processing_status", "ohsome_enriched");
        int trafficInitialized = initializeEnrichmentStatus("traffic_processing_status", "traffic_enriched");

        int weatherReset = resetEnrichmentProcessing("weather_processing_status");
        int berlinOpenDataReset = resetEnrichmentProcessing("berlin_open_data_processing_status");
        int ohsomeReset = resetEnrichmentProcessing("ohsome_processing_status");
        int trafficReset = resetEnrichmentProcessing("traffic_processing_status");

        if (ridesReset > 0 || weatherInitialized > 0 || berlinOpenDataInitialized > 0
                || ohsomeInitialized > 0 || trafficInitialized > 0
                || weatherReset > 0 || berlinOpenDataReset > 0 || ohsomeReset > 0 || trafficReset > 0) {
            log.info("Reset interrupted pipeline work: rides={}, initialized enrichment statuses={} "
                            + "(weather={}, berlinOpenData={}, ohsome={}, traffic={}), reset enrichment statuses={} "
                            + "(weather={}, berlinOpenData={}, ohsome={}, traffic={})",
                    ridesReset,
                    weatherInitialized + berlinOpenDataInitialized + ohsomeInitialized + trafficInitialized,
                    weatherInitialized,
                    berlinOpenDataInitialized,
                    ohsomeInitialized,
                    trafficInitialized,
                    weatherReset + berlinOpenDataReset + ohsomeReset + trafficReset,
                    weatherReset,
                    berlinOpenDataReset,
                    ohsomeReset,
                    trafficReset);
        }
    }

    @Transactional
    public List<UUID> claimPendingRidesForAnalysis(int batchSize) {
        return claimIds("""
                UPDATE rides
                SET status = 'ANALYZING'
                WHERE id IN (
                    SELECT id
                    FROM rides
                    WHERE status = 'PENDING'
                    ORDER BY start_time NULLS LAST, id
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id
                """, batchSize);
    }

    @Transactional
    public List<UUID> claimWeatherEvents(int batchSize) {
        return claimSegmentEvents("weather_processing_status", batchSize);
    }

    @Transactional
    public List<UUID> claimBerlinOpenDataEvents(int batchSize) {
        return claimSegmentEvents("berlin_open_data_processing_status", batchSize);
    }

    @Transactional
    public List<UUID> claimOhsomeEvents(int batchSize) {
        return claimSegmentEvents("ohsome_processing_status", batchSize);
    }

    @Transactional
    public List<UUID> claimTrafficEvents(int batchSize) {
        return claimSegmentEvents("traffic_processing_status", batchSize);
    }

    private List<UUID> claimSegmentEvents(String statusColumn, int batchSize) {
        return claimIds("""
                UPDATE segment_events
                SET %s = 'PROCESSING'
                WHERE id IN (
                    SELECT id
                    FROM segment_events
                    WHERE %s = 'PENDING'
                    ORDER BY event_timestamp NULLS LAST, id
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id
                """.formatted(statusColumn, statusColumn), batchSize);
    }

    private int initializeEnrichmentStatus(String statusColumn, String enrichedColumn) {
        return entityManager.createNativeQuery("""
                        UPDATE segment_events
                        SET %s = CASE WHEN %s = true THEN 'DONE' ELSE 'PENDING' END
                        WHERE %s IS NULL
                        """.formatted(statusColumn, enrichedColumn, statusColumn))
                .executeUpdate();
    }

    private int resetEnrichmentProcessing(String statusColumn) {
        return entityManager.createNativeQuery("""
                        UPDATE segment_events
                        SET %s = :pending
                        WHERE %s = :processing
                        """.formatted(statusColumn, statusColumn))
                .setParameter("pending", EnrichmentStatus.PENDING.name())
                .setParameter("processing", EnrichmentStatus.PROCESSING.name())
                .executeUpdate();
    }

    private List<UUID> claimIds(String sql, int batchSize) {
        return entityManager.createNativeQuery(sql)
                .setParameter("batchSize", Math.max(1, batchSize))
                .getResultList()
                .stream()
                .map(this::toUuid)
                .toList();
    }

    private UUID toUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }
}

package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayLocation;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.EnrichmentStatus;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RideIntent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEventType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficCondition;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficEnrichmentStatus;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficSourceType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.WindExposure;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public interface SegmentEventRepository extends JpaRepository<SegmentEvent, UUID> {

    @Query("SELECT se FROM SegmentEvent se JOIN FETCH se.segment WHERE se.id IN :ids")
    List<SegmentEvent> findWithSegmentByIdIn(List<UUID> ids);

    long countByWeatherEnriched(boolean weatherEnriched);

    long countByWeatherProcessingStatus(EnrichmentStatus status);

    long countByBerlinOpenDataEnriched(boolean berlinOpenDataEnriched);

    long countByBerlinOpenDataProcessingStatus(EnrichmentStatus status);

    long countByOhsomeEnriched(boolean ohsomeEnriched);

    long countByOhsomeProcessingStatus(EnrichmentStatus status);

    long countByTrafficEnriched(boolean trafficEnriched);

    long countByTrafficProcessingStatus(EnrichmentStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE SegmentEvent se
            SET se.weatherEnriched = true,
                se.weatherProcessingStatus = :status,
                se.temperature2m = :temperature2m,
                se.precipitation = :precipitation,
                se.windSpeed10m = :windSpeed10m,
                se.windDirection10m = :windDirection10m,
                se.weatherCode = :weatherCode,
                se.relativeWindAngleDegrees = :relativeWindAngleDegrees,
                se.windExposure = :windExposure
            WHERE se.id = :eventId
            """)
    int markWeatherEnriched(UUID eventId,
                            EnrichmentStatus status,
                            Double temperature2m,
                            Double precipitation,
                            Double windSpeed10m,
                            Double windDirection10m,
                            Integer weatherCode,
                            Double relativeWindAngleDegrees,
                            WindExposure windExposure);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE SegmentEvent se
            SET se.berlinOpenDataEnriched = true,
                se.berlinOpenDataProcessingStatus = :status
            WHERE se.id = :eventId
            """)
    int markBerlinOpenDataEnriched(UUID eventId, EnrichmentStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE SegmentEvent se
            SET se.ohsomeEnriched = true,
                se.ohsomeProcessingStatus = :status,
                se.surface = :surface,
                se.smoothness = :smoothness,
                se.lit = :lit,
                se.highway = :highway,
                se.cyclewayType = :cyclewayType,
                se.cyclewayLocation = :cyclewayLocation,
                se.cyclewaySurface = :cyclewaySurface,
                se.cyclewayWidth = :cyclewayWidth,
                se.bicycleOneway = :bicycleOneway
            WHERE se.id = :eventId
            """)
    int markOhsomeEnriched(UUID eventId,
                           EnrichmentStatus status,
                           String surface,
                           String smoothness,
                           String lit,
                           String highway,
                           CyclewayType cyclewayType,
                           CyclewayLocation cyclewayLocation,
                           String cyclewaySurface,
                           Double cyclewayWidth,
                           Boolean bicycleOneway);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE SegmentEvent se
            SET se.trafficEnriched = true,
                se.trafficProcessingStatus = :status,
                se.trafficVolumeKfz = :trafficVolumeKfz,
                se.trafficSpeedKfz = :trafficSpeedKfz,
                se.trafficVolumePkw = :trafficVolumePkw,
                se.trafficSpeedPkw = :trafficSpeedPkw,
                se.trafficVolumeLkw = :trafficVolumeLkw,
                se.trafficSpeedLkw = :trafficSpeedLkw,
                se.trafficSourceType = :trafficSourceType,
                se.trafficCondition = :trafficCondition,
                se.trafficEnrichmentStatus = :trafficEnrichmentStatus
            WHERE se.id = :eventId
            """)
    int markTrafficEnriched(UUID eventId,
                            EnrichmentStatus status,
                            Integer trafficVolumeKfz,
                            Double trafficSpeedKfz,
                            Integer trafficVolumePkw,
                            Double trafficSpeedPkw,
                            Integer trafficVolumeLkw,
                            Double trafficSpeedLkw,
                            TrafficSourceType trafficSourceType,
                            TrafficCondition trafficCondition,
                            TrafficEnrichmentStatus trafficEnrichmentStatus);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE SegmentEvent se SET se.weatherProcessingStatus = :status WHERE se.id = :eventId")
    int updateWeatherProcessingStatus(UUID eventId, EnrichmentStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE SegmentEvent se SET se.berlinOpenDataProcessingStatus = :status WHERE se.id = :eventId")
    int updateBerlinOpenDataProcessingStatus(UUID eventId, EnrichmentStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE SegmentEvent se SET se.ohsomeProcessingStatus = :status WHERE se.id = :eventId")
    int updateOhsomeProcessingStatus(UUID eventId, EnrichmentStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE SegmentEvent se SET se.trafficProcessingStatus = :status WHERE se.id = :eventId")
    int updateTrafficProcessingStatus(UUID eventId, EnrichmentStatus status);

    long countByEventType(SegmentEventType eventType);

    @Query("SELECT MIN(se.eventTimestamp) FROM SegmentEvent se")
    Long findEarliestEventTimestamp();

    @Query("SELECT MAX(se.eventTimestamp) FROM SegmentEvent se")
    Long findLatestEventTimestamp();

    @Query("""
            SELECT COUNT(se) FROM SegmentEvent se
            WHERE se.trafficEnrichmentStatus = berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficEnrichmentStatus.ENRICHED
            """)
    long countTrafficMeasuredEvents();

    /**
     * The enrichment flags narrow to events actually carrying that enrichment;
     * trafficMeasured means an attached detector measurement (status ENRICHED).
     */
    @Query("""
            SELECT se FROM SegmentEvent se
            JOIN FETCH se.ride
            WHERE se.segment.id = :segmentId
              AND (:eventType IS NULL OR se.eventType = :eventType)
              AND (:from IS NULL OR se.eventTimestamp >= :from)
              AND (:to IS NULL OR se.eventTimestamp <= :to)
              AND (:weatherEnriched = false OR se.weatherEnriched = true)
              AND (:ohsomeEnriched = false OR se.ohsomeEnriched = true)
              AND (:trafficEnriched = false OR se.trafficEnriched = true)
              AND (:trafficMeasured = false
                   OR se.trafficEnrichmentStatus = berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficEnrichmentStatus.ENRICHED)
              AND (:rideIntent IS NULL OR se.rideIntent = :rideIntent)
              AND (:trafficCondition IS NULL OR se.trafficCondition = :trafficCondition)
            ORDER BY se.eventTimestamp DESC
            """)
    List<SegmentEvent> findSegmentEventsForApi(
            Long segmentId,
            SegmentEventType eventType,
            Long from,
            Long to,
            boolean weatherEnriched,
            boolean ohsomeEnriched,
            boolean trafficEnriched,
            boolean trafficMeasured,
            RideIntent rideIntent,
            TrafficCondition trafficCondition,
            Pageable pageable
    );

    @Query("""
            SELECT se.segment.id,
                   SUM(CASE WHEN se.trafficEnriched = true THEN 1 ELSE 0 END),
                   SUM(CASE WHEN se.trafficEnrichmentStatus = berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficEnrichmentStatus.ENRICHED THEN 1 ELSE 0 END),
                   AVG(se.trafficVolumeKfz),
                   AVG(se.trafficSpeedKfz),
                   AVG(se.trafficVolumePkw),
                   AVG(se.trafficSpeedPkw),
                   AVG(se.trafficVolumeLkw),
                   AVG(se.trafficSpeedLkw)
            FROM SegmentEvent se
            WHERE se.segment.id IN :segmentIds
            GROUP BY se.segment.id
            """)
    List<Object[]> findTrafficStatsForSegments(List<Long> segmentIds);
}

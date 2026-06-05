package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEventType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface SegmentEventRepository extends JpaRepository<SegmentEvent, UUID> {

    List<SegmentEvent> findBySegmentId(Long segmentId);

    @Query("SELECT se FROM SegmentEvent se WHERE se.segment.id = :segmentId " +
            "AND se.eventTimestamp BETWEEN :from AND :to")
    List<SegmentEvent> findBySegmentIdAndTimeRange(Long segmentId, Long from, Long to);

    @Query("""
            SELECT se FROM SegmentEvent se
            JOIN FETCH se.segment
            JOIN FETCH se.ride
            WHERE se.segment.id = :segmentId
              AND (:eventType IS NULL OR se.eventType = :eventType)
              AND (:from IS NULL OR se.eventTimestamp >= :from)
              AND (:to IS NULL OR se.eventTimestamp <= :to)
            ORDER BY se.eventTimestamp DESC
            """)
    List<SegmentEvent> findSegmentEventsForApi(Long segmentId,
                                               SegmentEventType eventType,
                                               Long from,
                                               Long to,
                                               Pageable pageable);

    long countByEventType(SegmentEventType eventType);

    @Query("SELECT MIN(se.eventTimestamp) FROM SegmentEvent se")
    Long findMinEventTimestamp();

    @Query("SELECT MAX(se.eventTimestamp) FROM SegmentEvent se")
    Long findMaxEventTimestamp();

    @Query("SELECT se FROM SegmentEvent se JOIN FETCH se.segment WHERE se.weatherEnriched = false")
    List<SegmentEvent> findUnenrichedByWeather(Pageable pageable);

    long countByWeatherEnriched(boolean weatherEnriched);

    @Query("SELECT se FROM SegmentEvent se JOIN FETCH se.segment WHERE se.berlinOpenDataEnriched = false")
    List<SegmentEvent> findUnenrichedByBerlinOpenData(Pageable pageable);

    long countByBerlinOpenDataEnriched(boolean berlinOpenDataEnriched);

    @Query("SELECT se FROM SegmentEvent se JOIN FETCH se.segment WHERE se.ohsomeEnriched = false")
    List<SegmentEvent> findUnenrichedByOhsome(Pageable pageable);

    long countByOhsomeEnriched(boolean ohsomeEnriched);

    @Query("SELECT se FROM SegmentEvent se JOIN FETCH se.segment WHERE se.trafficEnriched = false")
    List<SegmentEvent> findUnenrichedByTraffic(Pageable pageable);

    long countByTrafficEnriched(boolean trafficEnriched);

    long countByTrafficConditionIsNotNull();

    @Query(value = """
            SELECT se.segment_id,
                   COUNT(*) FILTER (WHERE se.traffic_enriched = true) AS traffic_enriched_event_count,
                   COUNT(se.traffic_condition) AS traffic_measured_event_count,
                   AVG(se.traffic_volume_kfz) AS average_traffic_volume_kfz,
                   AVG(se.traffic_speed_kfz) AS average_traffic_speed_kfz,
                   AVG(se.traffic_volume_pkw) AS average_traffic_volume_pkw,
                   AVG(se.traffic_speed_pkw) AS average_traffic_speed_pkw,
                   AVG(se.traffic_volume_lkw) AS average_traffic_volume_lkw,
                   AVG(se.traffic_speed_lkw) AS average_traffic_speed_lkw,
                   mode() WITHIN GROUP (ORDER BY se.traffic_condition)
                       FILTER (WHERE se.traffic_condition IS NOT NULL) AS dominant_traffic_condition
            FROM segment_events se
            WHERE se.segment_id IN (:segmentIds)
            GROUP BY se.segment_id
            """, nativeQuery = true)
    List<Object[]> findTrafficStatsForSegments(Collection<Long> segmentIds);
}

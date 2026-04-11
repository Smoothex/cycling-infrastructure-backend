package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SegmentEventRepository extends JpaRepository<SegmentEvent, UUID> {

    List<SegmentEvent> findBySegmentId(Long segmentId);

    @Query("SELECT se FROM SegmentEvent se WHERE se.segment.id = :segmentId " +
            "AND se.eventTimestamp BETWEEN :from AND :to")
    List<SegmentEvent> findBySegmentIdAndTimeRange(Long segmentId, Long from, Long to);

    @Query("SELECT se FROM SegmentEvent se JOIN FETCH se.segment WHERE se.weatherEnriched = false")
    List<SegmentEvent> findUnenrichedByWeather(Pageable pageable);

    long countByWeatherEnriched(boolean weatherEnriched);

    @Query("SELECT se FROM SegmentEvent se JOIN FETCH se.segment WHERE se.berlinOpenDataEnriched = false")
    List<SegmentEvent> findUnenrichedByBerlinOpenData(Pageable pageable);

    long countByBerlinOpenDataEnriched(boolean berlinOpenDataEnriched);

    @Query("SELECT se FROM SegmentEvent se JOIN FETCH se.segment WHERE se.ohsomeEnriched = false")
    List<SegmentEvent> findUnenrichedByOhsome(Pageable pageable);

    long countByOhsomeEnriched(boolean ohsomeEnriched);
}

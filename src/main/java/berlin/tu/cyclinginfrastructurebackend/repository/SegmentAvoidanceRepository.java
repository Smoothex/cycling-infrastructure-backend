package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentAvoidance;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SegmentAvoidanceRepository extends JpaRepository<SegmentAvoidance, UUID> {

    List<SegmentAvoidance> findBySegmentId(Long segmentId);

    @Query("SELECT sa FROM SegmentAvoidance sa WHERE sa.segment.id = :segmentId " +
            "AND sa.avoidedAt BETWEEN :from AND :to")
    List<SegmentAvoidance> findBySegmentIdAndTimeRange(Long segmentId, Long from, Long to);

    @Query("SELECT sa FROM SegmentAvoidance sa JOIN FETCH sa.segment WHERE sa.weatherEnriched = false")
    List<SegmentAvoidance> findUnenrichedByWeather(Pageable pageable);

    long countByWeatherEnriched(boolean weatherEnriched);

    @Query("SELECT sa FROM SegmentAvoidance sa JOIN FETCH sa.segment WHERE sa.berlinOpenDataEnriched = false")
    List<SegmentAvoidance> findUnenrichedByBerlinOpenData(Pageable pageable);

    long countByBerlinOpenDataEnriched(boolean berlinOpenDataEnriched);

    @Query("SELECT sa FROM SegmentAvoidance sa JOIN FETCH sa.segment WHERE sa.ohsomeEnriched = false")
    List<SegmentAvoidance> findUnenrichedByOhsome(Pageable pageable);

    long countByOhsomeEnriched(boolean ohsomeEnriched);
}

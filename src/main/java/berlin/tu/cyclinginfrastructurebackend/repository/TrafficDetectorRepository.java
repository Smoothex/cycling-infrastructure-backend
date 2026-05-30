package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.TrafficDetector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TrafficDetectorRepository extends JpaRepository<TrafficDetector, UUID> {

    Optional<TrafficDetector> findByDetNameAlt(String detNameAlt);

    @Query(value = """
            SELECT td.*
            FROM traffic_detectors td
            JOIN street_segments s ON s.id = :segmentId
            WHERE td.location IS NOT NULL
              AND s.geometry IS NOT NULL
              AND ST_DWithin(td.location::geography, s.geometry::geography, :radiusMeters)
            ORDER BY ST_Distance(td.location::geography, s.geometry::geography)
            LIMIT :limit
            """, nativeQuery = true)
    List<TrafficDetector> findNearestToSegment(Long segmentId, double radiusMeters, int limit);
}

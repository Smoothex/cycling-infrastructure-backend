package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Repository
public interface StreetSegmentRepository extends JpaRepository<StreetSegment, Long> {

    @Modifying
    @Transactional
    @Query("UPDATE StreetSegment s SET s.usageCount = s.usageCount + 1, " +
            "s.avoidanceRatio = CAST(s.avoidanceCount AS double) / (s.usageCount + 1 + s.avoidanceCount) " +
            "WHERE s.id = :id")
    int incrementUsage(Long id);

    @Modifying
    @Transactional
    @Query("UPDATE StreetSegment s SET s.avoidanceCount = s.avoidanceCount + 1, " +
            "s.avoidanceRatio = CAST(s.avoidanceCount + 1 AS double) / (s.usageCount + s.avoidanceCount + 1) " +
            "WHERE s.id IN :ids")
    void bulkIncrementAvoidance(Collection<Long> ids);

    @Modifying
    @Query(value = """
        INSERT INTO street_segments (id, street_name, geometry, usage_count, avoidance_count, avoidance_ratio, surface)
        VALUES (:id, :name, CAST(:geom AS geometry), 0, 0, NULL, :surface)
        ON CONFLICT (id) DO UPDATE SET surface = EXCLUDED.surface
    """, nativeQuery = true)
    void upsertSegment(Long id, String name, Object geom, String surface);

    /** Segments with highest avoidance ratio, filtered by minimum total observations to reduce noise. */
    @Query("SELECT s FROM StreetSegment s " +
            "WHERE (s.usageCount + s.avoidanceCount) >= :minSampleSize " +
            "AND s.avoidanceRatio >= :minAvoidanceRatio " +
            "ORDER BY s.avoidanceRatio DESC")
    List<StreetSegment> findSuspiciousSegments(double minAvoidanceRatio, int minSampleSize, Pageable pageable);

    /**
     * Checks if a GraphHopper edge is within a given distance (meters) of a reference geometry.
     * Uses ST_DWithin with geography cast for meter-accurate distance on WGS84 data.
     */
    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM street_segments s
            WHERE s.id = :edgeId
            AND ST_DWithin(s.geometry::geography, CAST(:referenceGeom AS geography), :distanceMeters)
        )
        """, nativeQuery = true)
    boolean isEdgeWithinDistance(int edgeId, Object referenceGeom, double distanceMeters);
}

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
    @Transactional
    @Query("UPDATE StreetSegment s SET s.preferenceCount = s.preferenceCount + 1, " +
            "s.preferenceRatio = CAST(s.preferenceCount + 1 AS double) / (s.usageCount + s.preferenceCount + 1) " +
            "WHERE s.id IN :ids")
    void bulkIncrementPreference(Collection<Long> ids);

    @Modifying
    @Query(value = """
        INSERT INTO street_segments (
            id, street_name, geometry, usage_count, avoidance_count, avoidance_ratio,
            preference_count, preference_ratio, gradient_percent
        )
        VALUES (:id, :name, CAST(:geom AS geometry), 0, 0, NULL, 0, NULL, :gradientPercent)
        ON CONFLICT (id) DO UPDATE SET
            gradient_percent = COALESCE(EXCLUDED.gradient_percent, street_segments.gradient_percent)
    """, nativeQuery = true)
    void upsertSegment(Long id, String name, Object geom, Double gradientPercent);

    /** Segments with highest avoidance ratio, filtered by minimum total observations to reduce noise. */
    @Query("SELECT s FROM StreetSegment s " +
            "WHERE (s.usageCount + s.avoidanceCount) >= :minSampleSize " +
            "AND s.avoidanceRatio >= :minAvoidanceRatio " +
            "ORDER BY s.avoidanceRatio DESC")
    List<StreetSegment> findSuspiciousSegments(double minAvoidanceRatio, int minSampleSize, Pageable pageable);

    @Query(value = """
        SELECT s.*
        FROM street_segments s
        WHERE s.geometry IS NOT NULL
          AND (:minSampleSize <= 0 OR (s.usage_count + s.avoidance_count + s.preference_count) >= :minSampleSize)
          AND (
              (:minAvoidanceRatio IS NULL AND :minPreferenceRatio IS NULL)
              OR (:minAvoidanceRatio IS NOT NULL AND s.avoidance_ratio >= :minAvoidanceRatio)
              OR (:minPreferenceRatio IS NOT NULL AND s.preference_ratio >= :minPreferenceRatio)
          )
        ORDER BY GREATEST(COALESCE(s.avoidance_ratio, 0), COALESCE(s.preference_ratio, 0)) DESC,
                 (s.usage_count + s.avoidance_count + s.preference_count) DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<StreetSegment> findSegmentsForMap(Double minAvoidanceRatio,
                                           Double minPreferenceRatio,
                                           int minSampleSize,
                                           int limit);

    @Query(value = """
        SELECT s.*
        FROM street_segments s
        WHERE s.geometry IS NOT NULL
          AND ST_Intersects(s.geometry, ST_MakeEnvelope(:minLon, :minLat, :maxLon, :maxLat, 4326))
          AND (:minSampleSize <= 0 OR (s.usage_count + s.avoidance_count + s.preference_count) >= :minSampleSize)
          AND (
              (:minAvoidanceRatio IS NULL AND :minPreferenceRatio IS NULL)
              OR (:minAvoidanceRatio IS NOT NULL AND s.avoidance_ratio >= :minAvoidanceRatio)
              OR (:minPreferenceRatio IS NOT NULL AND s.preference_ratio >= :minPreferenceRatio)
          )
        ORDER BY GREATEST(COALESCE(s.avoidance_ratio, 0), COALESCE(s.preference_ratio, 0)) DESC,
                 (s.usage_count + s.avoidance_count + s.preference_count) DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<StreetSegment> findSegmentsForMapWithinBbox(Double minAvoidanceRatio,
                                                     Double minPreferenceRatio,
                                                     int minSampleSize,
                                                     double minLon,
                                                     double minLat,
                                                     double maxLon,
                                                     double maxLat,
                                                     int limit);

    @Query("SELECT COUNT(s) FROM StreetSegment s " +
            "WHERE (s.usageCount + s.avoidanceCount + s.preferenceCount) > 0")
    long countObservedSegments();

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

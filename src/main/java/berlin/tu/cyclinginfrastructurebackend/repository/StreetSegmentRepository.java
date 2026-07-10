package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
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
            "s.avoidanceRatio = CAST(s.avoidanceCount AS double) / (s.usageCount + 1 + s.avoidanceCount), " +
            "s.preferenceRatio = CAST(s.preferenceCount AS double) / (s.usageCount + 1 + s.preferenceCount) " +
            "WHERE s.id = :id")
    int incrementUsage(Long id);

    /**
     * Set-based increment for a whole batch of segments in one round trip. The
     * FOR UPDATE subquery locks the rows in ascending id order, preserving the
     * in order to prevent deadlock.
     */
    @Modifying
    @Transactional
    @Query(value = """
        UPDATE street_segments s
        SET avoidance_count = s.avoidance_count + 1,
            avoidance_ratio = CAST(s.avoidance_count + 1 AS double precision)
                              / (s.usage_count + s.avoidance_count + 1)
        FROM (SELECT id FROM street_segments WHERE id IN :ids ORDER BY id FOR UPDATE) locked
        WHERE s.id = locked.id
        """, nativeQuery = true)
    int incrementAvoidanceAll(Collection<Long> ids);

    @Modifying
    @Transactional
    @Query(value = """
        UPDATE street_segments s
        SET preference_count = s.preference_count + 1,
            preference_ratio = CAST(s.preference_count + 1 AS double precision)
                               / (s.usage_count + s.preference_count + 1)
        FROM (SELECT id FROM street_segments WHERE id IN :ids ORDER BY id FOR UPDATE) locked
        WHERE s.id = locked.id
        """, nativeQuery = true)
    int incrementPreferenceAll(Collection<Long> ids);

    @Modifying
    @Transactional
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

    @Query("SELECT s.id FROM StreetSegment s WHERE s.id IN :ids")
    List<Long> findExistingIds(Collection<Long> ids);

    /**
     * Segments with highest avoidance ratio, filtered by minimum total observations to
     * reduce noise. When event-level criteria are active (time window and/or enrichment
     * filters), a segment qualifies only if at least one of its events matches all of
     * them at once. trafficMeasured means an attached detector measurement
     * (traffic_enrichment_status = 'ENRICHED'), matching the per-segment
     * trafficMeasuredEventCount and the tile export semantics.
     */
    @Query(value = """
            SELECT s.* FROM street_segments s
            WHERE (s.usage_count + s.avoidance_count) >= :minSampleSize
              AND s.avoidance_ratio >= :minAvoidanceRatio
              AND (:eventCriteriaActive = false OR EXISTS (
                    SELECT 1 FROM segment_events e
                    WHERE e.segment_id = s.id
                      AND e.event_timestamp >= :from
                      AND e.event_timestamp <= :to
                      AND (:weatherEnriched = false OR e.weather_enriched)
                      AND (:ohsomeEnriched = false OR e.ohsome_enriched)
                      AND (:trafficEnriched = false OR e.traffic_enriched)
                      AND (:trafficMeasured = false OR e.traffic_enrichment_status = 'ENRICHED')))
            ORDER BY s.avoidance_ratio DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<StreetSegment> findSuspiciousSegments(
            double minAvoidanceRatio,
            int minSampleSize,
            boolean eventCriteriaActive,
            long from,
            long to,
            boolean weatherEnriched,
            boolean ohsomeEnriched,
            boolean trafficEnriched,
            boolean trafficMeasured,
            int limit
    );

    @Query("""
            SELECT COUNT(s) FROM StreetSegment s
            WHERE (s.usageCount + s.avoidanceCount + s.preferenceCount) > 0
            """)
    long countObservedSegments();

    @Query(value = """
            SELECT * FROM street_segments s
            WHERE s.geometry IS NOT NULL
              AND (s.usage_count + s.avoidance_count + s.preference_count) >= :minSampleSize
              AND (
                    COALESCE(s.avoidance_ratio, 0) >= :minAvoidanceRatio
                 OR COALESCE(s.preference_ratio, 0) >= :minPreferenceRatio
              )
              AND (:eventCriteriaActive = false OR EXISTS (
                    SELECT 1 FROM segment_events e
                    WHERE e.segment_id = s.id
                      AND e.event_timestamp >= :from
                      AND e.event_timestamp <= :to
                      AND (:weatherEnriched = false OR e.weather_enriched)
                      AND (:ohsomeEnriched = false OR e.ohsome_enriched)
                      AND (:trafficEnriched = false OR e.traffic_enriched)
                      AND (:trafficMeasured = false OR e.traffic_enrichment_status = 'ENRICHED')))
            ORDER BY GREATEST(COALESCE(s.avoidance_ratio, 0), COALESCE(s.preference_ratio, 0)) DESC,
                     (s.usage_count + s.avoidance_count + s.preference_count) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<StreetSegment> findSegmentsForMap(
            double minAvoidanceRatio,
            double minPreferenceRatio,
            int minSampleSize,
            boolean eventCriteriaActive,
            long from,
            long to,
            boolean weatherEnriched,
            boolean ohsomeEnriched,
            boolean trafficEnriched,
            boolean trafficMeasured,
            int limit
    );

    @Query(value = """
            SELECT * FROM street_segments s
            WHERE s.geometry IS NOT NULL
              AND (s.usage_count + s.avoidance_count + s.preference_count) >= :minSampleSize
              AND (
                    COALESCE(s.avoidance_ratio, 0) >= :minAvoidanceRatio
                 OR COALESCE(s.preference_ratio, 0) >= :minPreferenceRatio
              )
              AND (:eventCriteriaActive = false OR EXISTS (
                    SELECT 1 FROM segment_events e
                    WHERE e.segment_id = s.id
                      AND e.event_timestamp >= :from
                      AND e.event_timestamp <= :to
                      AND (:weatherEnriched = false OR e.weather_enriched)
                      AND (:ohsomeEnriched = false OR e.ohsome_enriched)
                      AND (:trafficEnriched = false OR e.traffic_enriched)
                      AND (:trafficMeasured = false OR e.traffic_enrichment_status = 'ENRICHED')))
              AND ST_Intersects(
                    s.geometry,
                    ST_MakeEnvelope(:minLon, :minLat, :maxLon, :maxLat, 4326)
              )
            ORDER BY s.id
            LIMIT :limit
            """, nativeQuery = true)
    List<StreetSegment> findSegmentsForMapWithinBbox(
            double minAvoidanceRatio,
            double minPreferenceRatio,
            int minSampleSize,
            boolean eventCriteriaActive,
            long from,
            long to,
            boolean weatherEnriched,
            boolean ohsomeEnriched,
            boolean trafficEnriched,
            boolean trafficMeasured,
            double minLon,
            double minLat,
            double maxLon,
            double maxLat,
            int limit
    );

    /**
     * Returns the ids of the given edges that lie within the given distance (meters) of a
     * reference geometry. Uses ST_DWithin with geography cast for meter-accurate distance
     * on WGS84 data.
     */
    @Query(value = """
        SELECT s.id
        FROM street_segments s
        WHERE s.id IN :edgeIds
          AND ST_DWithin(s.geometry::geography, CAST(:referenceGeom AS geography), :distanceMeters)
        """, nativeQuery = true)
    List<Long> findEdgeIdsWithinDistance(Collection<Long> edgeIds, Object referenceGeom, double distanceMeters);
}

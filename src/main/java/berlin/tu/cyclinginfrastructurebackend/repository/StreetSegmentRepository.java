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

    /**
     * Ranks same-named street corridors (spatially connected clusters of segments sharing
     * a street name, via ST_ClusterDBSCAN) by distinct rides carrying an avoidance or
     * preference signal within the given window. Each row's geometry bbox and top-segment
     * id let the frontend fit the map to the corridor and pre-select a representative
     * segment. rank selects both the qualifying threshold (:minRideCount) and the sort/mode
     * tie-break column; scary_incidents counts nearby (25m) incidents flagged scary within
     * the same time window and ride-intent filter.
     */
    @Query(value = """
            WITH filtered_events AS (
                SELECT e.segment_id, e.ride_id, e.event_type
                FROM segment_events e
                WHERE e.event_timestamp >= :from AND e.event_timestamp <= :to
                  AND (:rideIntent = '' OR e.ride_intent = :rideIntent)
            ),
            event_segments AS (
                SELECT DISTINCT segment_id FROM filtered_events
            ),
            clustered_segments AS (
                SELECT s.id,
                       s.street_name,
                       s.geometry,
                       ST_ClusterDBSCAN(ST_Transform(s.geometry, 25833), 75, 1)
                           OVER (PARTITION BY LOWER(TRIM(s.street_name))) AS corridor_cluster
                FROM street_segments s
                JOIN event_segments es ON es.segment_id = s.id
                WHERE s.street_name IS NOT NULL AND TRIM(s.street_name) <> ''
                  AND LOWER(TRIM(s.street_name)) <> 'unknown'
                  AND s.geometry IS NOT NULL
            ),
            corridor_geometries AS (
                SELECT LOWER(TRIM(street_name)) AS normalized_name,
                       MIN(street_name) AS street_name,
                       corridor_cluster,
                       COUNT(*) AS segment_count,
                       ST_UnaryUnion(ST_Collect(geometry)) AS geometry,
                       ST_XMin(ST_Extent(geometry)) AS min_lon,
                       ST_YMin(ST_Extent(geometry)) AS min_lat,
                       ST_XMax(ST_Extent(geometry)) AS max_lon,
                       ST_YMax(ST_Extent(geometry)) AS max_lat
                FROM clustered_segments
                GROUP BY LOWER(TRIM(street_name)), corridor_cluster
            ),
            corridor_counts AS (
                SELECT LOWER(TRIM(cs.street_name)) AS normalized_name,
                       cs.corridor_cluster,
                       COUNT(DISTINCT fe.ride_id) FILTER (WHERE fe.event_type = 'AVOIDANCE') AS avoidance_rides,
                       COUNT(DISTINCT fe.ride_id) FILTER (WHERE fe.event_type = 'PREFERENCE') AS preference_rides,
                       COUNT(*) FILTER (WHERE fe.event_type = 'AVOIDANCE') AS avoidance_events,
                       COUNT(*) FILTER (WHERE fe.event_type = 'PREFERENCE') AS preference_events,
                       mode() WITHIN GROUP (ORDER BY fe.segment_id)
                           FILTER (WHERE fe.event_type = :rank) AS top_segment_id
                FROM filtered_events fe
                JOIN clustered_segments cs ON cs.id = fe.segment_id
                GROUP BY LOWER(TRIM(cs.street_name)), cs.corridor_cluster
            ),
            candidates AS (
                SELECT cg.*, cc.avoidance_rides, cc.preference_rides,
                       cc.avoidance_events, cc.preference_events, cc.top_segment_id
                FROM corridor_geometries cg
                JOIN corridor_counts cc
                  ON cc.normalized_name = cg.normalized_name
                 AND cc.corridor_cluster = cg.corridor_cluster
                WHERE CASE WHEN :rank = 'PREFERENCE' THEN cc.preference_rides ELSE cc.avoidance_rides END >= :minRideCount
                ORDER BY CASE WHEN :rank = 'PREFERENCE' THEN cc.preference_rides ELSE cc.avoidance_rides END DESC,
                         CASE WHEN :rank = 'PREFERENCE' THEN cc.preference_events ELSE cc.avoidance_events END DESC,
                         cg.street_name
                LIMIT :limit
            )
            SELECT c.street_name,
                   c.avoidance_rides,
                   c.preference_rides,
                   c.avoidance_events,
                   c.preference_events,
                   c.segment_count,
                   c.min_lon,
                   c.min_lat,
                   c.max_lon,
                   c.max_lat,
                   c.top_segment_id,
                   COUNT(DISTINCT i.id) FILTER (
                       WHERE i.scary = true
                         AND (:rideIntent = '' OR ir.ride_intent = :rideIntent)
                   ) AS scary_incidents
            FROM candidates c
            LEFT JOIN incidents i
              ON i.location IS NOT NULL
             AND i.timestamp >= :from AND i.timestamp <= :to
             AND ST_DWithin(c.geometry::geography, i.location::geography, 25)
            LEFT JOIN rides ir ON ir.id = i.ride_id
            GROUP BY c.street_name, c.avoidance_rides, c.preference_rides,
                     c.avoidance_events, c.preference_events, c.segment_count,
                     c.min_lon, c.min_lat, c.max_lon, c.max_lat, c.top_segment_id
            ORDER BY CASE WHEN :rank = 'PREFERENCE' THEN c.preference_rides ELSE c.avoidance_rides END DESC,
                     CASE WHEN :rank = 'PREFERENCE' THEN c.preference_events ELSE c.avoidance_events END DESC,
                     c.street_name
            """, nativeQuery = true)
    List<Object[]> findCorridorRankings(String rank, int minRideCount, int limit, long from, long to, String rideIntent);
}

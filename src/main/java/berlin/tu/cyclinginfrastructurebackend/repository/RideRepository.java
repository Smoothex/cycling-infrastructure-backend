package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public interface RideRepository extends JpaRepository<Ride, UUID> {
    @Query("SELECT r.originalFilename FROM Ride r")
    Set<String> findAllOriginalFilenames();

    @Query("SELECT r.id FROM Ride r WHERE r.status = :status")
    List<UUID> findIdsByStatus(Status status, Pageable pageable);

    @Query("""
        SELECT DISTINCT r FROM Ride r
        LEFT JOIN FETCH r.ridePoints
        LEFT JOIN FETCH r.traversedEdgeIds
        WHERE r.id = :id
        """)
    Optional<Ride> findWithPointsAndEdgesById(UUID id);

    long countByStatus(Status status);

    /**
     * Returns ride-level detour metrics computed from persisted geometries.
     * Each row: [ride_id (UUID), actual_m (double), shortest_m (double), detour_pct (double)]
     */
    @Query(value = """
        SELECT r.id                                                          AS ride_id,
               ST_Length(r.trajectory::geography)                             AS actual_m,
               ST_Length(r.shortest_path::geography)                         AS shortest_m,
               ROUND(
                 (ST_Length(r.trajectory::geography)
                  - ST_Length(r.shortest_path::geography))
                 / NULLIF(ST_Length(r.shortest_path::geography), 0) * 100, 2
               )                                                             AS detour_pct
        FROM rides r
        WHERE r.shortest_path IS NOT NULL
          AND r.trajectory    IS NOT NULL
        ORDER BY detour_pct DESC
        """, nativeQuery = true)
    List<Object[]> findRidesWithDetourRatio(Pageable pageable);

    /**
     * For a single ride, returns every shortest-path edge also recorded
     * as an avoidance by <em>any</em> ride, together with the avoidance metadata.
     * Each row: [edge_id (long), segment_street_name (String), avoidance_count (long),
     *            total_rides_avoiding (long)]
     */
    @Query(value = """
        SELECT spe.edge_id,
               ss.street_name,
               ss.avoidance_count,
               COUNT(DISTINCT sa.ride_id) AS total_rides_avoiding
        FROM ride_shortest_path_edges spe
        JOIN street_segments ss       ON ss.id = spe.edge_id
        JOIN segment_avoidances sa    ON sa.segment_id = spe.edge_id
        WHERE spe.ride_id = :rideId
        GROUP BY spe.edge_id, ss.street_name, ss.avoidance_count
        ORDER BY ss.avoidance_count DESC
        """, nativeQuery = true)
    List<Object[]> findShortestPathEdgesWithAvoidances(UUID rideId);
}

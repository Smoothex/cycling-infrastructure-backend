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

}

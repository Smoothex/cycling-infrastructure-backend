package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RideIntent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public interface RideRepository extends JpaRepository<Ride, UUID> {
    @Query("SELECT r.originalFilename FROM Ride r")
    Set<String> findAllOriginalFilenames();

    /**
     * Calculates which share of the shortest path's physical length is covered by a
     * metric buffer around the actual route.
     */
    @Query(value = """
            WITH paths AS (
                SELECT ST_Transform(ST_GeomFromText(:shortestPathWkt, 4326), 25833) AS shortest_path,
                       ST_Transform(ST_GeomFromText(:actualPathWkt, 4326), 25833) AS actual_path
            ), lengths AS (
                SELECT ST_Length(shortest_path) AS shortest_length,
                       ST_Length(ST_Intersection(
                           shortest_path,
                           ST_Buffer(actual_path, :proximityMeters)
                       )) AS covered_length
                FROM paths
            )
            SELECT CASE
                       WHEN shortest_length <= 0 THEN NULL
                       ELSE LEAST(1.0, GREATEST(0.0, covered_length / shortest_length))
                   END
            FROM lengths
            """, nativeQuery = true)
    Double calculateSpatialLengthOverlap(
            @Param("actualPathWkt") String actualPathWkt,
            @Param("shortestPathWkt") String shortestPathWkt,
            @Param("proximityMeters") double proximityMeters);

    long countByStatus(Status status);

    long countByRouteComparisonType(RouteComparisonType routeComparisonType);

    @Query("""
            SELECT r.routeComparisonType, COUNT(r)
            FROM Ride r
            WHERE r.routeComparisonType IS NOT NULL
              AND r.startTime >= :from
              AND r.startTime <= :to
              AND (:rideIntent IS NULL OR r.rideIntent = :rideIntent)
            GROUP BY r.routeComparisonType
            """)
    List<Object[]> countRouteComparisonTypes(
            @Param("from") long from,
            @Param("to") long to,
            @Param("rideIntent") RideIntent rideIntent);

    @Query(value = """
            SELECT route_comparison_type,
                   COUNT(*),
                   PERCENTILE_CONT(0.25) WITHIN GROUP (
                       ORDER BY ((actual_distance - shortest_path_distance) / shortest_path_distance) * 100.0
                   ),
                   PERCENTILE_CONT(0.50) WITHIN GROUP (
                       ORDER BY ((actual_distance - shortest_path_distance) / shortest_path_distance) * 100.0
                   ),
                   PERCENTILE_CONT(0.75) WITHIN GROUP (
                       ORDER BY ((actual_distance - shortest_path_distance) / shortest_path_distance) * 100.0
                   )
            FROM rides
            WHERE route_comparison_type IN ('LOCAL_DETOUR', 'CORRIDOR_ALTERNATIVE')
              AND actual_distance IS NOT NULL
              AND shortest_path_distance IS NOT NULL
              AND shortest_path_distance > 0
              AND start_time >= :from
              AND start_time <= :to
              AND (CAST(:rideIntent AS varchar) IS NULL
                   OR ride_intent = CAST(:rideIntent AS varchar))
            GROUP BY route_comparison_type
            ORDER BY route_comparison_type
            """, nativeQuery = true)
    List<Object[]> findDetourImpactStats(
            @Param("from") long from,
            @Param("to") long to,
            @Param("rideIntent") String rideIntent);
}

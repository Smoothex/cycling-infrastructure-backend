package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Repository
public interface RideRepository extends JpaRepository<Ride, UUID> {
    @Query("SELECT r.originalFilename FROM Ride r")
    Set<String> findAllOriginalFilenames();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE Ride r SET r.status = :status WHERE r.id = :id")
    int updateStatus(UUID id, Status status);

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
}

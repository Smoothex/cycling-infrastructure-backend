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
    boolean existsByOriginalFilename(String originalFilename);

    @Query("SELECT r.originalFilename FROM Ride r")
    Set<String> findAllOriginalFilenames();

    @Query("SELECT r FROM Ride r LEFT JOIN FETCH r.ridePoints WHERE r.id = :id")
    Optional<Ride> findWithPointsById(UUID id);

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
}

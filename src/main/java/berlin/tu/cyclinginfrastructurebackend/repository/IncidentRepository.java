package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.Incident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IncidentRepository extends JpaRepository<Incident, UUID> {

    /**
     * Finds incidents whose location is within the given distance (meters) of a segment's geometry.
     * Uses ST_DWithin with geography cast for meter-accurate distance on WGS84 data.
     */
    @Query(value = """
            SELECT i.* FROM incidents i
            JOIN street_segments s ON ST_DWithin(
                i.location::geography,
                s.geometry::geography,
                :distanceMeters
            )
            WHERE s.id = :segmentId
            """, nativeQuery = true)
    List<Incident> findIncidentsNearSegment(Long segmentId, double distanceMeters);

    List<Incident> findByRideId(UUID rideId);
}


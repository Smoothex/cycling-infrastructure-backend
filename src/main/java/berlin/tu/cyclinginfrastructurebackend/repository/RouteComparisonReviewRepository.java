package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.RouteComparisonReview;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RouteComparisonReviewRepository extends JpaRepository<RouteComparisonReview, UUID> {
    @EntityGraph(attributePaths = "issueCodes")
    Optional<RouteComparisonReview> findByRideId(UUID rideId);

    @EntityGraph(attributePaths = "issueCodes")
    List<RouteComparisonReview> findAllByRideIdIn(Collection<UUID> rideIds);
}

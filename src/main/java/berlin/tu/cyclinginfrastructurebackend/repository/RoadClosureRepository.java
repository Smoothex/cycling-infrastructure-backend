package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.RoadClosure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoadClosureRepository extends JpaRepository<RoadClosure, UUID> {

    Optional<RoadClosure> findByFeedId(String feedId);

    boolean existsByFeedIdStartingWith(String prefix);

    /**
     * Road closures whose validity range overlaps the given epoch-ms window.
     * Null bounds are open; a null validTo means the closure is open-ended.
     */
    @Query("""
            SELECT c FROM RoadClosure c
            WHERE (:to IS NULL OR c.validFrom <= :to)
              AND (:from IS NULL OR c.validTo IS NULL OR c.validTo >= :from)
            """)
    List<RoadClosure> findOverlapping(@Param("from") Long from, @Param("to") Long to);
}

package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

@Repository
public interface StreetSegmentRepository extends JpaRepository<StreetSegment, Long> {

    @Modifying
    @Transactional
    @Query("UPDATE StreetSegment s SET s.usageCount = s.usageCount + 1 WHERE s.id = :id")
    int incrementUsage(Long id);

    @Modifying
    @Transactional
    @Query("UPDATE StreetSegment s SET s.avoidanceCount = s.avoidanceCount + 1 WHERE s.id = :id")
    int incrementAvoidance(Integer id);

    @Modifying
    @Transactional
    @Query("UPDATE StreetSegment s SET s.avoidanceCount = s.avoidanceCount + 1 WHERE s.id IN :ids")
    void bulkIncrementAvoidance(Collection<Long> ids);

    @Modifying
    @Query(value = """
        INSERT INTO street_segments (id, street_name, geometry, usage_count, avoidance_count)
        VALUES (:id, :name, CAST(:geom AS geometry), 0, 0)
        ON CONFLICT (id) DO NOTHING
        """, nativeQuery = true)
    void upsertSegment(Long id, String name, Object geom);
}

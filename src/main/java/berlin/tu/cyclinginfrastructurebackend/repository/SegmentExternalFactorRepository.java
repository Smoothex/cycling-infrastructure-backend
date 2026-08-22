package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentExternalFactor;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ExternalFactorType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SegmentExternalFactorRepository extends JpaRepository<SegmentExternalFactor, UUID> {

    List<SegmentExternalFactor> findBySegmentId(Long segmentId);

    List<SegmentExternalFactor> findBySegmentIdAndFactorType(Long segmentId, ExternalFactorType factorType);

    /** Finds external factors whose validity window overlaps the given time range */
    @Query("SELECT f FROM SegmentExternalFactor f WHERE f.segment.id = :segmentId " +
            "AND f.validFrom <= :to AND (f.validTo IS NULL OR f.validTo >= :from)")
    List<SegmentExternalFactor> findOverlapping(Long segmentId, Long from, Long to);

    /**
     * Finds disruption factors from one source that could overlap events in the
     * supplied time range. Callers still perform an exact timestamp check per event.
     */
    @Query("""
            SELECT f FROM SegmentExternalFactor f
            WHERE f.segment.id = :segmentId
              AND f.source = :source
              AND f.factorType IN :factorTypes
              AND f.validFrom <= :to
              AND (f.validTo IS NULL OR f.validTo >= :from)
            """)
    List<SegmentExternalFactor> findDisruptionsOverlapping(
            @Param("segmentId") Long segmentId,
            @Param("source") String source,
            @Param("factorTypes") Collection<ExternalFactorType> factorTypes,
            @Param("from") Long from,
            @Param("to") Long to
    );

    /** Check if an external factor already exist for this segment, type, and start time */
    boolean existsBySegmentIdAndFactorTypeAndSourceAndValidFrom(
            Long segmentId,
            ExternalFactorType factorType,
            String source,
            Long validFrom
    );

    Optional<SegmentExternalFactor> findFirstBySegmentIdAndFactorTypeAndValidFrom(
            Long segmentId,
            ExternalFactorType factorType,
            Long validFrom
    );
}

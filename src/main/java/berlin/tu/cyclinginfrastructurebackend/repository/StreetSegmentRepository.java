package berlin.tu.cyclinginfrastructurebackend.repository;

import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StreetSegmentRepository extends JpaRepository<StreetSegment, Long> {
}


package berlin.tu.cyclinginfrastructurebackend.repository;

import java.util.Map;

public interface StreetSegmentUsageRepository {

    /**
     * Applies all usage deltas in one PostgreSQL statement.
     *
     * @param usageByEdgeId distinct edge ids and their positive occurrence counts, in lock order
     * @return the number of updated segment rows
     */
    int incrementUsageCounts(Map<Long, Integer> usageByEdgeId);
}

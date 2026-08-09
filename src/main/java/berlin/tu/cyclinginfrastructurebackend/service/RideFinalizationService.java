package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEventType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentEventRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Commits all finalized ride-related state in one short transaction. */
@Service
public class RideFinalizationService {

    private final RideRepository rideRepository;
    private final StreetSegmentRepository streetSegmentRepository;
    private final SegmentEventRepository segmentEventRepository;

    public RideFinalizationService(RideRepository rideRepository,
                                   StreetSegmentRepository streetSegmentRepository,
                                   SegmentEventRepository segmentEventRepository) {
        this.rideRepository = rideRepository;
        this.streetSegmentRepository = streetSegmentRepository;
        this.segmentEventRepository = segmentEventRepository;
    }

    @Transactional
    public Ride finalizeRide(Ride ride,
                             Map<Long, Integer> usageByEdgeId,
                             DetourAnalysisResult analysisResult) {
        Objects.requireNonNull(ride, "ride");
        Objects.requireNonNull(usageByEdgeId, "usageByEdgeId");
        Objects.requireNonNull(analysisResult, "analysisResult");
        rejectNonFinalStatus(ride.getStatus());

        Ride managedRide = rideRepository.saveAndFlush(ride);

        TreeSet<Long> segmentIds = new TreeSet<>(usageByEdgeId.keySet());
        addIds(segmentIds, analysisResult.avoidedEdgeBearings().keySet());
        addIds(segmentIds, analysisResult.chosenEdgeBearings().keySet());
        List<Long> sortedSegmentIds = List.copyOf(segmentIds);

        if (!sortedSegmentIds.isEmpty()) {
            List<Long> lockedIds = streetSegmentRepository.lockForUpdate(sortedSegmentIds);
            if (lockedIds.size() != sortedSegmentIds.size()) {
                throw new IllegalStateException("Expected to lock " + sortedSegmentIds.size()
                        + " street segments, but locked " + lockedIds.size());
            }
        }

        if (!usageByEdgeId.isEmpty()) {
            int updated = streetSegmentRepository.incrementUsageCounts(usageByEdgeId);
            requireUpdatedCount("usage", usageByEdgeId.size(), updated);
        }

        List<Long> avoidedIds = sortedIds(analysisResult.avoidedEdgeBearings().keySet());
        if (!avoidedIds.isEmpty()) {
            int updated = streetSegmentRepository.incrementAvoidanceAll(avoidedIds);
            requireUpdatedCount("avoidance", avoidedIds.size(), updated);
        }

        List<Long> chosenIds = sortedIds(analysisResult.chosenEdgeBearings().keySet());
        if (!chosenIds.isEmpty()) {
            int updated = streetSegmentRepository.incrementPreferenceAll(chosenIds);
            requireUpdatedCount("preference", chosenIds.size(), updated);
        }

        List<SegmentEvent> events = buildEvents(managedRide, sortedSegmentIds, analysisResult);
        if (!events.isEmpty()) {
            segmentEventRepository.saveAll(events);
        }
        return managedRide;
    }

    private void rejectNonFinalStatus(Status status) {
        if (status != Status.PROCESSED && status != Status.SKIPPED) {
            throw new IllegalArgumentException("Ride must be finalized before persistence; status was " + status);
        }
    }

    private void addIds(Collection<Long> target, Collection<Integer> source) {
        source.stream().map(Integer::longValue).forEach(target::add);
    }

    private List<Long> sortedIds(Collection<Integer> edgeIds) {
        return edgeIds.stream().map(Integer::longValue).sorted().toList();
    }

    private void requireUpdatedCount(String counter, int expected, int actual) {
        if (actual != expected) {
            throw new IllegalStateException("Expected to update " + expected + " " + counter
                    + " segment counters, but updated " + actual);
        }
    }

    private List<SegmentEvent> buildEvents(Ride ride,
                                           List<Long> sortedSegmentIds,
                                           DetourAnalysisResult result) {
        List<SegmentEvent> events = new ArrayList<>();
        for (Long segmentId : sortedSegmentIds) {
            int edgeId = segmentId.intValue();
            boolean avoided = result.avoidedEdgeBearings().containsKey(edgeId);
            boolean chosen = result.chosenEdgeBearings().containsKey(edgeId);
            if (!avoided && !chosen) {
                continue;
            }
            StreetSegment segment = streetSegmentRepository.getReferenceById(segmentId);
            if (avoided) {
                events.add(SegmentEvent.of(
                        SegmentEventType.AVOIDANCE,
                        segment,
                        ride,
                        timestampOrRideStart(result.avoidedEdgeTimestamps().get(edgeId), ride),
                        result.avoidedEdgeBearings().get(edgeId)));
            }
            if (chosen) {
                events.add(SegmentEvent.of(
                        SegmentEventType.PREFERENCE,
                        segment,
                        ride,
                        timestampOrRideStart(result.chosenEdgeTimestamps().get(edgeId), ride),
                        result.chosenEdgeBearings().get(edgeId)));
            }
        }
        return events;
    }

    private Long timestampOrRideStart(Long timestamp, Ride ride) {
        return timestamp != null ? timestamp : ride.getStartTime();
    }
}

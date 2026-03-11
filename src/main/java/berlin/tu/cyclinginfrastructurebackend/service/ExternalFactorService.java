package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentExternalFactor;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ExternalFactorType;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentExternalFactorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates external factor enrichment and provides query methods.
 * All {@link ExternalDataProvider} implementations are auto-collected by Spring
 * and invoked when enriching a segment.
 */
@Service
public class ExternalFactorService {

    private static final Logger log = LoggerFactory.getLogger(ExternalFactorService.class);

    private final List<ExternalDataProvider> providers;
    private final SegmentExternalFactorRepository factorRepository;

    public ExternalFactorService(List<ExternalDataProvider> providers,
                                 SegmentExternalFactorRepository factorRepository) {
        this.providers = providers;
        this.factorRepository = factorRepository;
    }

    /**
     * Runs all registered providers for the given segment and time window.
     */
    public void enrichSegment(StreetSegment segment, Long fromEpochMillis, Long toEpochMillis) {
        for (ExternalDataProvider provider : providers) {
            try {
                provider.enrichSegment(segment, fromEpochMillis, toEpochMillis);
            } catch (Exception e) {
                log.error("Provider {} failed for segment {}: {}",
                        provider.getClass().getSimpleName(), segment.getId(), e.getMessage());
            }
        }
    }

    public List<SegmentExternalFactor> getFactorsForSegment(Long segmentId) {
        return factorRepository.findBySegmentId(segmentId);
    }

    public List<SegmentExternalFactor> getFactorsByType(Long segmentId, ExternalFactorType type) {
        return factorRepository.findBySegmentIdAndFactorType(segmentId, type);
    }

    /**
     * Finds external factors that overlap a specific point in time for a segment.
     */
    public List<SegmentExternalFactor> getFactorsAtTime(Long segmentId, Long epochMillis) {
        return factorRepository.findOverlapping(segmentId, epochMillis, epochMillis);
    }

    /**
     * Finds external factors that overlap the given time range for a segment.
     */
    public List<SegmentExternalFactor> getFactorsInRange(Long segmentId, Long from, Long to) {
        return factorRepository.findOverlapping(segmentId, from, to);
    }
}


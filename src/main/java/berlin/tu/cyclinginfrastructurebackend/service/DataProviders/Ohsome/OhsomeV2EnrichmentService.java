package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.repository.OhsomeEnrichmentBatchRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import berlin.tu.cyclinginfrastructurebackend.service.PipelineActivityTracker;
import berlin.tu.cyclinginfrastructurebackend.service.TileBuildService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Drains historical OSM enrichment from immutable monthly v2 snapshots.
 *
 * <p>All network and GeoParquet work happens before the corresponding results are
 * finalized by the repository. A failed snapshot load releases the claimed
 * segment/month pairs so a later scheduled run can retry them.</p>
 */
@Service
public class OhsomeV2EnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(OhsomeV2EnrichmentService.class);

    private final OhsomeEnrichmentBatchRepository batchRepository;
    private final StreetSegmentRepository streetSegmentRepository;
    private final OhsomeSnapshotCache snapshotCache;
    private final OhsomeGeoParquetReader snapshotReader;
    private final OhsomeTagMapper tagMapper;
    private final OhsomeV2Properties properties;
    private final TileBuildService tileBuildService;
    private final PipelineActivityTracker pipelineActivityTracker;

    public OhsomeV2EnrichmentService(OhsomeEnrichmentBatchRepository batchRepository,
                                     StreetSegmentRepository streetSegmentRepository,
                                     OhsomeSnapshotCache snapshotCache,
                                     OhsomeGeoParquetReader snapshotReader,
                                     OhsomeTagMapper tagMapper,
                                     OhsomeV2Properties properties,
                                     TileBuildService tileBuildService,
                                     PipelineActivityTracker pipelineActivityTracker) {
        this.batchRepository = batchRepository;
        this.streetSegmentRepository = streetSegmentRepository;
        this.snapshotCache = snapshotCache;
        this.snapshotReader = snapshotReader;
        this.tagMapper = tagMapper;
        this.properties = properties;
        this.tileBuildService = tileBuildService;
        this.pipelineActivityTracker = pipelineActivityTracker;
    }

    public boolean isQuotaPaused() {
        return snapshotCache.isQuotaPaused();
    }

    /**
     * Processes all currently pending Ohsome work. The batch size counts distinct
     * segment/month pairs, not individual events.
     */
    public DrainSummary drainPending(int batchSize) {
        if (!batchRepository.hasPendingWork()) {
            return DrainSummary.noWork();
        }

        Instant startedAt = Instant.now();
        MutableSummary summary = new MutableSummary();
        try (PipelineActivityTracker.Activity ignored = pipelineActivityTracker.beginWork()) {
            summary.invalidEvents = batchRepository.markInvalidPendingEvents();
            OhsomeTile loadedTile = null;
            YearMonth loadedMonth = null;
            OhsomeSnapshot loadedSnapshot = null;
            while (!Thread.currentThread().isInterrupted()) {
                var optionalClaim = batchRepository.claimNextBatch(properties.getGridSizeDegrees(), Math.max(1, batchSize));
                if (optionalClaim.isEmpty()) {
                    break;
                }
                OhsomeClaim claim = optionalClaim.get();
                List<OhsomeWorkItem> workItems = claim.items();
                try {
                    if (!claim.tile().equals(loadedTile) || !claim.month().equals(loadedMonth)) {
                        loadedSnapshot = null; // Release the previous spatial index before reading another file.
                        OhsomeCachedSnapshot cached = snapshotCache.ensureSnapshot(claim.tile(), claim.month());
                        summary.validatedSnapshots++;
                        loadedSnapshot = snapshotReader.read(cached.path());
                        loadedTile = claim.tile();
                        loadedMonth = claim.month();
                        summary.loadedSnapshots++;
                    }
                    PreparedBatch prepared = prepareResults(workItems, loadedSnapshot);
                    summary.updatedEvents += batchRepository.finalizeBatch(prepared.results());
                    summary.addCommitted(prepared);
                    summary.batches++;
                    log.info("Ohsome batch: tile={}, month={}, pairs={}, matched={}, unmatched={}, ambiguous={}, errors={}",
                            claim.tile().id(), claim.month(), workItems.size(), prepared.matchedPairs(),
                            prepared.unmatchedPairs(), prepared.ambiguousPairs(), prepared.errorPairs());
                } catch (IOException | RuntimeException exception) {
                    if (exception instanceof OhsomeSnapshotCacheException cacheException
                            && cacheException.failureKind() == OhsomeSnapshotCacheException.FailureKind.INVALID_REQUEST) {
                        try {
                            summary.updatedEvents += batchRepository.finalizeBatch(
                                    workItems.stream().map(OhsomeBatchResult::error).toList());
                        } catch (RuntimeException finalizationFailure) {
                            summary.releasedEvents += batchRepository.releaseBatch(workItems);
                            throw finalizationFailure;
                        }
                        summary.batches++;
                        summary.processedPairs += workItems.size();
                        summary.errorPairs += workItems.size();
                        loadedTile = null;
                        log.error("Ohsome rejected tile={}, month={}; marked {} pairs ERROR: {}",
                                claim.tile().id(), claim.month(), workItems.size(), exception.getMessage());
                        continue;
                    }
                    summary.releasedEvents += batchRepository.releaseBatch(workItems);
                    if (exception instanceof OhsomeSnapshotCacheException cacheException) {
                        summary.cacheFailure = cacheException.failureKind().name();
                    } else {
                        summary.processingFailure = exception.getClass().getSimpleName();
                    }
                    if (exception instanceof OhsomeSnapshotCacheException cacheException
                            && cacheException.failureKind() == OhsomeSnapshotCacheException.FailureKind.QUOTA_EXCEEDED) {
                        log.warn("Ohsome paused at tile={}, month={}; claimed events released to PENDING: {}",
                                claim.tile().id(), claim.month(), exception.getMessage());
                    } else {
                        log.error("Ohsome stopped at tile={}, month={}; claimed events released to PENDING: {}",
                                claim.tile().id(), claim.month(), exception.getMessage(), exception);
                    }
                    break;
                }
            }

            if (Thread.currentThread().isInterrupted()) {
                summary.interrupted = true;
            }
            return finish(summary, startedAt, summary.cacheFailure == null && summary.processingFailure == null && !summary.interrupted);
        } finally {
            // Some batches may already have committed even if a later claim, load, or
            // final-status query fails. Invalidate tiles exactly once for those writes.
            if (summary.changedEvents() > 0) {
                tileBuildService.markDataChanged();
            }
        }
    }

    private PreparedBatch prepareResults(List<OhsomeWorkItem> workItems, OhsomeSnapshot snapshot) {
        List<Long> segmentIds = workItems.stream().map(OhsomeWorkItem::segmentId).toList();
        Map<Long, StreetSegment> segmentsById = new HashMap<>();
        streetSegmentRepository.findAllById(segmentIds)
                .forEach(segment -> segmentsById.put(segment.getId(), segment));

        List<OhsomeBatchResult> results = new ArrayList<>(workItems.size());
        long matchedPairs = 0;
        long unmatchedPairs = 0;
        long ambiguousPairs = 0;
        long errorPairs = 0;
        for (OhsomeWorkItem item : workItems) {
            StreetSegment segment = segmentsById.get(item.segmentId());
            if (segment == null || segment.getGeometry() == null) {
                results.add(OhsomeBatchResult.error(item));
                errorPairs++;
                continue;
            }

            try {
                OhsomeFeatureMatch match = snapshot.match(segment.getGeometry(), segment.getStreetName());
                switch (match.outcome()) {
                    case MATCHED -> {
                        OhsomeInfrastructureAttributes attributes = tagMapper.map(
                                match.feature().orElseThrow().tags());
                        results.add(OhsomeBatchResult.matched(item, attributes));
                        matchedPairs++;
                    }
                    case AMBIGUOUS -> {
                        results.add(OhsomeBatchResult.noData(item));
                        ambiguousPairs++;
                    }
                    case NO_MATCH -> {
                        results.add(OhsomeBatchResult.noData(item));
                        unmatchedPairs++;
                    }
                }
            } catch (RuntimeException exception) {
                results.add(OhsomeBatchResult.error(item));
                errorPairs++;
                log.warn("Could not match Ohsome data for segment {} in {}: {}",
                        item.segmentId(), item.month(), exception.getMessage());
            }
        }
        return new PreparedBatch(
                List.copyOf(results), matchedPairs, unmatchedPairs, ambiguousPairs, errorPairs);
    }

    private DrainSummary finish(MutableSummary summary, Instant startedAt, boolean completed) {
        OhsomeEnrichmentBatchRepository.ProcessingCounts counts = batchRepository.processingCounts();
        Duration elapsed = Duration.between(startedAt, Instant.now());
        boolean fullyDrained = completed && counts.pending() == 0 && counts.processing() == 0;
        DrainSummary result = summary.toResult(fullyDrained, counts, elapsed);
        logSummary(result);
        return result;
    }

    private void logSummary(DrainSummary summary) {
        log.info("""
                
                ═══════════════════════════════════════════════════════════════════
                OHSOME V2 ENRICHMENT SUMMARY
                ───────────────────────────────────────────────────────────────────
                Snapshot cache:       validated={}  loaded={}
                Segment/month pairs:  processed={}  matched={}  unmatched={}  ambiguous={}  errors={}
                Events updated:       matched/no-data/error={}  invalid={}
                Final statuses:       pending={}  processing={}  enriched={}  no-data={}  errors={}
                Completion:           completed={}  cache-failure={}  processing-failure={}  elapsed={}
                ═══════════════════════════════════════════════════════════════════
                """,
                summary.validatedSnapshots(), summary.loadedSnapshots(),
                summary.processedPairs(), summary.matchedPairs(), summary.unmatchedPairs(),
                summary.ambiguousPairs(), summary.errorPairs(), summary.updatedEvents(),
                summary.invalidEvents(), summary.pendingEvents(),
                summary.processingEvents(), summary.enrichedEvents(), summary.noDataEvents(),
                summary.errorEvents(), summary.completed(), valueOrNone(summary.cacheFailure()),
                valueOrNone(summary.processingFailure()), summary.elapsed());
    }

    private String valueOrNone(String value) {
        return value == null ? "none" : value;
    }

    private record PreparedBatch(
            List<OhsomeBatchResult> results,
            long matchedPairs,
            long unmatchedPairs,
            long ambiguousPairs,
            long errorPairs
    ) {
    }

    private static final class MutableSummary {
        private long validatedSnapshots;
        private long loadedSnapshots;
        private long batches;
        private long processedPairs;
        private long matchedPairs;
        private long unmatchedPairs;
        private long ambiguousPairs;
        private long errorPairs;
        private long updatedEvents;
        private long invalidEvents;
        private long releasedEvents;
        private boolean interrupted;
        private String cacheFailure;
        private String processingFailure;

        private long changedEvents() {
            return updatedEvents + invalidEvents;
        }

        private void addCommitted(PreparedBatch prepared) {
            processedPairs += prepared.results().size();
            matchedPairs += prepared.matchedPairs();
            unmatchedPairs += prepared.unmatchedPairs();
            ambiguousPairs += prepared.ambiguousPairs();
            errorPairs += prepared.errorPairs();
        }

        private DrainSummary toResult(
                boolean completed,
                OhsomeEnrichmentBatchRepository.ProcessingCounts counts,
                Duration elapsed
        ) {
            return new DrainSummary(
                    completed,
                    validatedSnapshots,
                    loadedSnapshots,
                    batches,
                    processedPairs,
                    matchedPairs,
                    unmatchedPairs,
                    ambiguousPairs,
                    errorPairs,
                    updatedEvents,
                    invalidEvents,
                    releasedEvents,
                    counts.pending(),
                    counts.processing(),
                    counts.enriched(),
                    counts.noData(),
                    counts.errors(),
                    interrupted,
                    cacheFailure,
                    processingFailure,
                    elapsed
            );
        }
    }

    public record DrainSummary(
            boolean completed,
            long validatedSnapshots,
            long loadedSnapshots,
            long batches,
            long processedPairs,
            long matchedPairs,
            long unmatchedPairs,
            long ambiguousPairs,
            long errorPairs,
            long updatedEvents,
            long invalidEvents,
            long releasedEvents,
            long pendingEvents,
            long processingEvents,
            long enrichedEvents,
            long noDataEvents,
            long errorEvents,
            boolean interrupted,
            String cacheFailure,
            String processingFailure,
            Duration elapsed
    ) {
        private static DrainSummary noWork() {
            return new DrainSummary(
                    true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, false, null, null, Duration.ZERO
            );
        }
    }
}

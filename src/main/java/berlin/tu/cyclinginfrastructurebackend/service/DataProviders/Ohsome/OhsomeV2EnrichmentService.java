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
import java.time.ZoneOffset;
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
            DatasetBounds bounds = datasetBounds();
            OhsomeEnrichmentBatchRepository.UnsupportedCounts unsupported =
                    batchRepository.finalizeUnsupported(
                            bounds.fromInclusive(),
                            bounds.toExclusive(),
                            bounds.minLon(),
                            bounds.minLat(),
                            bounds.maxLon(),
                            bounds.maxLat()
                    );
            summary.outsideTimeRange = unsupported.outsideTimeRange();
            summary.outsideArea = unsupported.outsideArea();

            if (!batchRepository.hasPendingWork()) {
                return finish(summary, startedAt, true);
            }

            final OhsomeSnapshotCatalog catalog;
            try {
                catalog = snapshotCache.ensureReady();
                summary.validatedSnapshots = catalog.snapshots().size();
            } catch (OhsomeSnapshotCacheException exception) {
                summary.cacheFailure = exception.failureKind().name();
                log.error("Ohsome v2 cache is not ready ({}): {}. Supported events remain PENDING.",
                        exception.failureKind(), exception.getMessage());
                return finish(summary, startedAt, false);
            }

            YearMonth loadedMonth = null;
            OhsomeSnapshot loadedSnapshot = null;
            while (!Thread.currentThread().isInterrupted()) {
                List<OhsomeWorkItem> workItems = batchRepository.claimNextBatch(
                        bounds.fromInclusive(), bounds.toExclusive(), Math.max(1, batchSize));
                if (workItems.isEmpty()) {
                    break;
                }

                YearMonth month = workItems.getFirst().month();
                try {
                    if (!month.equals(loadedMonth)) {
                        loadedSnapshot = snapshotReader.read(catalog.snapshot(month).path());
                        loadedMonth = month;
                        summary.loadedSnapshots++;
                        log.info("Loaded Ohsome snapshot {} with {} line features.",
                                month, loadedSnapshot.featureCount());
                    }

                    PreparedBatch prepared = prepareResults(workItems, loadedSnapshot);
                    summary.updatedEvents += batchRepository.finalizeBatch(prepared.results());
                    summary.addCommitted(prepared);
                    summary.batches++;
                } catch (IOException | RuntimeException exception) {
                    int released = batchRepository.releaseBatch(workItems);
                    summary.releasedEvents += released;
                    summary.processingFailure = exception.getClass().getSimpleName();
                    log.error("Ohsome enrichment stopped while processing {}: {}. "
                                    + "Released {} events to PENDING.",
                            month, exception.getMessage(), released, exception);
                    break;
                }
            }

            if (Thread.currentThread().isInterrupted()) {
                summary.interrupted = true;
            }
            return finish(summary, startedAt, summary.processingFailure == null && !summary.interrupted);
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
                Events updated:       matched/no-data/error={}  outside-time={}  outside-area={}
                Final statuses:       pending={}  processing={}  enriched={}  no-data={}  errors={}
                Completion:           completed={}  cache-failure={}  processing-failure={}  elapsed={}
                ═══════════════════════════════════════════════════════════════════
                """,
                summary.validatedSnapshots(), summary.loadedSnapshots(),
                summary.processedPairs(), summary.matchedPairs(), summary.unmatchedPairs(),
                summary.ambiguousPairs(), summary.errorPairs(), summary.updatedEvents(),
                summary.outsideTimeRange(), summary.outsideArea(), summary.pendingEvents(),
                summary.processingEvents(), summary.enrichedEvents(), summary.noDataEvents(),
                summary.errorEvents(), summary.completed(), valueOrNone(summary.cacheFailure()),
                valueOrNone(summary.processingFailure()), summary.elapsed());
    }

    private String valueOrNone(String value) {
        return value == null ? "none" : value;
    }

    private DatasetBounds datasetBounds() {
        long fromInclusive = properties.startMonthValue().atDay(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long toExclusive = properties.endMonthValue().plusMonths(1).atDay(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        List<Double> bbox = properties.getBbox();
        return new DatasetBounds(
                fromInclusive, toExclusive,
                bbox.get(0), bbox.get(1), bbox.get(2), bbox.get(3)
        );
    }

    private record DatasetBounds(
            long fromInclusive,
            long toExclusive,
            double minLon,
            double minLat,
            double maxLon,
            double maxLat
    ) {
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
        private long outsideTimeRange;
        private long outsideArea;
        private long releasedEvents;
        private boolean interrupted;
        private String cacheFailure;
        private String processingFailure;

        private long changedEvents() {
            return updatedEvents + outsideTimeRange + outsideArea;
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
                    outsideTimeRange,
                    outsideArea,
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
            long outsideTimeRange,
            long outsideArea,
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
                    true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, false, null, null, Duration.ZERO
            );
        }
    }
}

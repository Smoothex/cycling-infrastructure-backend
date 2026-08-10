package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayLocation;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayType;
import berlin.tu.cyclinginfrastructurebackend.repository.OhsomeEnrichmentBatchRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import berlin.tu.cyclinginfrastructurebackend.service.PipelineActivityTracker;
import berlin.tu.cyclinginfrastructurebackend.service.TileBuildService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OhsomeV2EnrichmentServiceTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final YearMonth JANUARY = YearMonth.of(2024, 1);
    private static final long SUPPORTED_FROM = monthStart(JANUARY);
    private static final long SUPPORTED_TO_EXCLUSIVE = monthStart(JANUARY.plusMonths(1));
    private static final double MIN_LON = 13.0;
    private static final double MIN_LAT = 52.0;
    private static final double MAX_LON = 14.0;
    private static final double MAX_LAT = 53.0;
    private static final int BATCH_SIZE = 25;
    private static final OhsomeEnrichmentBatchRepository.ProcessingCounts NO_PROCESSING_COUNTS =
            new OhsomeEnrichmentBatchRepository.ProcessingCounts(0, 0, 0, 0, 0);

    private OhsomeEnrichmentBatchRepository batchRepository;
    private StreetSegmentRepository streetSegmentRepository;
    private OhsomeSnapshotCache snapshotCache;
    private OhsomeGeoParquetReader snapshotReader;
    private TileBuildService tileBuildService;
    private PipelineActivityTracker pipelineActivityTracker;
    private OhsomeV2EnrichmentService service;

    @BeforeEach
    void setUp() {
        batchRepository = mock(OhsomeEnrichmentBatchRepository.class);
        streetSegmentRepository = mock(StreetSegmentRepository.class);
        snapshotCache = mock(OhsomeSnapshotCache.class);
        snapshotReader = mock(OhsomeGeoParquetReader.class);
        tileBuildService = mock(TileBuildService.class);
        pipelineActivityTracker = new PipelineActivityTracker();

        OhsomeV2Properties properties = new OhsomeV2Properties();
        properties.setStartMonth(JANUARY.toString());
        properties.setEndMonth(JANUARY.toString());
        properties.setBbox(List.of(MIN_LON, MIN_LAT, MAX_LON, MAX_LAT));
        properties.validate();

        service = new OhsomeV2EnrichmentService(
                batchRepository,
                streetSegmentRepository,
                snapshotCache,
                snapshotReader,
                new OhsomeTagMapper(),
                properties,
                tileBuildService,
                pipelineActivityTracker);

        when(batchRepository.finalizeUnsupported(
                SUPPORTED_FROM, SUPPORTED_TO_EXCLUSIVE, MIN_LON, MIN_LAT, MAX_LON, MAX_LAT))
                .thenReturn(new OhsomeEnrichmentBatchRepository.UnsupportedCounts(0, 0));
        when(batchRepository.processingCounts()).thenReturn(NO_PROCESSING_COUNTS);
    }

    @Test
    void noPendingWorkAvoidsCacheAndAllPersistence() {
        when(batchRepository.hasPendingWork()).thenReturn(false);

        OhsomeV2EnrichmentService.DrainSummary summary = service.drainPending(BATCH_SIZE);

        assertThat(summary.completed()).isTrue();
        assertThat(summary.processedPairs()).isZero();
        assertThat(summary.elapsed()).isZero();
        assertThat(pipelineActivityTracker.isActive()).isFalse();
        verify(batchRepository, never()).finalizeUnsupported(
                anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble());
        verify(batchRepository, never()).processingCounts();
        verifyNoInteractions(snapshotCache, snapshotReader, streetSegmentRepository, tileBuildService);
    }

    @Test
    void finalizesUnsupportedEventsBeforeOpeningTheCache() throws Exception {
        givenSupportedWorkRemains();
        OhsomeEnrichmentBatchRepository.UnsupportedCounts unsupported =
                new OhsomeEnrichmentBatchRepository.UnsupportedCounts(2, 3);
        when(batchRepository.finalizeUnsupported(
                SUPPORTED_FROM, SUPPORTED_TO_EXCLUSIVE, MIN_LON, MIN_LAT, MAX_LON, MAX_LAT))
                .thenReturn(unsupported);
        when(snapshotCache.ensureReady()).thenReturn(new OhsomeSnapshotCatalog(Map.of()));
        when(batchRepository.claimNextBatch(SUPPORTED_FROM, SUPPORTED_TO_EXCLUSIVE, BATCH_SIZE))
                .thenReturn(List.of());

        OhsomeV2EnrichmentService.DrainSummary summary = service.drainPending(BATCH_SIZE);

        InOrder order = inOrder(batchRepository, snapshotCache);
        order.verify(batchRepository).hasPendingWork();
        order.verify(batchRepository).finalizeUnsupported(
                SUPPORTED_FROM, SUPPORTED_TO_EXCLUSIVE, MIN_LON, MIN_LAT, MAX_LON, MAX_LAT);
        order.verify(batchRepository).hasPendingWork();
        order.verify(snapshotCache).ensureReady();
        order.verify(batchRepository).claimNextBatch(
                SUPPORTED_FROM, SUPPORTED_TO_EXCLUSIVE, BATCH_SIZE);
        assertThat(summary.completed()).isTrue();
        assertThat(summary.outsideTimeRange()).isEqualTo(2);
        assertThat(summary.outsideArea()).isEqualTo(3);
        verify(tileBuildService, times(1)).markDataChanged();
        verifyNoInteractions(snapshotReader, streetSegmentRepository);
    }

    @Test
    void cacheFailureClaimsNothingAndLeavesSupportedEventsPending() {
        givenSupportedWorkRemains();
        OhsomeSnapshotCacheException failure = new OhsomeSnapshotCacheException(
                OhsomeSnapshotCacheException.FailureKind.LOCAL_IO, "cache unavailable");
        when(snapshotCache.ensureReady()).thenThrow(failure);
        when(batchRepository.processingCounts()).thenReturn(
                new OhsomeEnrichmentBatchRepository.ProcessingCounts(7, 0, 0, 0, 0));

        OhsomeV2EnrichmentService.DrainSummary summary = service.drainPending(BATCH_SIZE);

        assertThat(summary.completed()).isFalse();
        assertThat(summary.cacheFailure()).isEqualTo("LOCAL_IO");
        assertThat(summary.pendingEvents()).isEqualTo(7);
        assertThat(summary.processingEvents()).isZero();
        verify(batchRepository, never()).claimNextBatch(anyLong(), anyLong(), anyInt());
        verify(batchRepository, never()).finalizeBatch(anyList());
        verify(batchRepository, never()).releaseBatch(anyList());
        verifyNoInteractions(snapshotReader, streetSegmentRepository, tileBuildService);
    }

    @Test
    void finalizesMatchedAmbiguousNoMatchAndErrorResultsInOneMappedBatch() throws Exception {
        givenSupportedWorkRemains();
        Path snapshotPath = Path.of("january.parquet");
        when(snapshotCache.ensureReady()).thenReturn(catalog(snapshotPath));

        LineString matchedLine = eastWestLine(52.40, 13.20, 13.201);
        LineString ambiguousLine = eastWestLine(52.60, 13.50, 13.501);
        Map<String, String> matchedTags = Map.ofEntries(
                Map.entry("name", "Matched Street"),
                Map.entry("surface", "asphalt"),
                Map.entry("smoothness", "excellent"),
                Map.entry("lit", "yes"),
                Map.entry("highway", "residential"),
                Map.entry("cycleway:right", "track"),
                Map.entry("cycleway:right:surface", "red-asphalt"),
                Map.entry("cycleway:right:width", "2.25"),
                Map.entry("oneway:bicycle", "no"));
        OhsomeSnapshot snapshot = new OhsomeSnapshot(List.of(
                new OhsomeFeature(100, matchedLine, matchedTags),
                new OhsomeFeature(200, offsetNorth(ambiguousLine, 2), Map.of()),
                new OhsomeFeature(201, offsetNorth(ambiguousLine, -2), Map.of())));
        when(snapshotReader.read(snapshotPath)).thenReturn(snapshot);

        List<OhsomeWorkItem> workItems = List.of(
                new OhsomeWorkItem(10, JANUARY),
                new OhsomeWorkItem(20, JANUARY),
                new OhsomeWorkItem(30, JANUARY),
                new OhsomeWorkItem(40, JANUARY));
        when(batchRepository.claimNextBatch(SUPPORTED_FROM, SUPPORTED_TO_EXCLUSIVE, BATCH_SIZE))
                .thenReturn(workItems, List.of());
        StreetSegment matched = segment(10, "Matched Street", matchedLine);
        StreetSegment ambiguous = segment(20, null, ambiguousLine);
        StreetSegment unmatched = segment(30, null, eastWestLine(52.80, 13.80, 13.801));
        StreetSegment missingGeometry = segment(40, "Broken", null);
        when(streetSegmentRepository.findAllById(List.of(10L, 20L, 30L, 40L)))
                .thenReturn(List.of(matched, ambiguous, unmatched, missingGeometry));
        when(batchRepository.finalizeBatch(anyList())).thenReturn(9);
        when(batchRepository.finalizeUnsupported(
                SUPPORTED_FROM, SUPPORTED_TO_EXCLUSIVE, MIN_LON, MIN_LAT, MAX_LON, MAX_LAT))
                .thenReturn(new OhsomeEnrichmentBatchRepository.UnsupportedCounts(1, 2));
        when(batchRepository.processingCounts()).thenReturn(
                new OhsomeEnrichmentBatchRepository.ProcessingCounts(0, 0, 4, 4, 1));

        OhsomeV2EnrichmentService.DrainSummary summary = service.drainPending(BATCH_SIZE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OhsomeBatchResult>> resultsCaptor = ArgumentCaptor.forClass(List.class);
        verify(batchRepository, times(1)).finalizeBatch(resultsCaptor.capture());
        OhsomeInfrastructureAttributes expectedAttributes = new OhsomeInfrastructureAttributes(
                "asphalt", "excellent", "yes", "residential",
                CyclewayType.TRACK, CyclewayLocation.RIGHT,
                "red-asphalt", 2.25, false);
        assertThat(resultsCaptor.getValue()).containsExactly(
                OhsomeBatchResult.matched(workItems.get(0), expectedAttributes),
                OhsomeBatchResult.noData(workItems.get(1)),
                OhsomeBatchResult.noData(workItems.get(2)),
                OhsomeBatchResult.error(workItems.get(3)));
        assertThat(summary.completed()).isTrue();
        assertThat(summary.validatedSnapshots()).isEqualTo(1);
        assertThat(summary.loadedSnapshots()).isEqualTo(1);
        assertThat(summary.batches()).isEqualTo(1);
        assertThat(summary.processedPairs()).isEqualTo(4);
        assertThat(summary.matchedPairs()).isEqualTo(1);
        assertThat(summary.ambiguousPairs()).isEqualTo(1);
        assertThat(summary.unmatchedPairs()).isEqualTo(1);
        assertThat(summary.errorPairs()).isEqualTo(1);
        assertThat(summary.updatedEvents()).isEqualTo(9);
        assertThat(summary.outsideTimeRange()).isEqualTo(1);
        assertThat(summary.outsideArea()).isEqualTo(2);
        verify(snapshotReader, times(1)).read(snapshotPath);
        verify(tileBuildService, times(1)).markDataChanged();
    }

    @Test
    void snapshotReadFailureReleasesTheClaimedWork() throws Exception {
        givenSupportedWorkRemains();
        Path snapshotPath = Path.of("corrupt-january.parquet");
        List<OhsomeWorkItem> workItems = List.of(new OhsomeWorkItem(10, JANUARY));
        when(snapshotCache.ensureReady()).thenReturn(catalog(snapshotPath));
        when(batchRepository.claimNextBatch(SUPPORTED_FROM, SUPPORTED_TO_EXCLUSIVE, BATCH_SIZE))
                .thenReturn(workItems);
        when(snapshotReader.read(snapshotPath)).thenThrow(new IOException("corrupt snapshot"));
        when(batchRepository.releaseBatch(workItems)).thenReturn(3);
        when(batchRepository.processingCounts()).thenReturn(
                new OhsomeEnrichmentBatchRepository.ProcessingCounts(3, 0, 0, 0, 0));

        OhsomeV2EnrichmentService.DrainSummary summary = service.drainPending(BATCH_SIZE);

        assertThat(summary.completed()).isFalse();
        assertThat(summary.processingFailure()).isEqualTo("IOException");
        assertThat(summary.releasedEvents()).isEqualTo(3);
        assertThat(summary.loadedSnapshots()).isZero();
        assertThat(summary.processedPairs()).isZero();
        verify(batchRepository).releaseBatch(workItems);
        verify(batchRepository, never()).finalizeBatch(anyList());
        verifyNoInteractions(streetSegmentRepository, tileBuildService);
    }

    @Test
    void finalizationFailureReleasesItsClaimAndMarksTilesOnceForEarlierWrites() throws Exception {
        givenSupportedWorkRemains();
        Path snapshotPath = Path.of("january.parquet");
        LineString firstLine = eastWestLine(52.40, 13.20, 13.201);
        LineString secondLine = eastWestLine(52.60, 13.50, 13.501);
        OhsomeSnapshot snapshot = new OhsomeSnapshot(List.of(
                new OhsomeFeature(100, firstLine, Map.of("name", "First Street", "surface", "asphalt")),
                new OhsomeFeature(200, secondLine, Map.of("name", "Second Street", "surface", "paved"))));
        List<OhsomeWorkItem> firstBatch = List.of(new OhsomeWorkItem(10, JANUARY));
        List<OhsomeWorkItem> failingBatch = List.of(new OhsomeWorkItem(20, JANUARY));
        when(snapshotCache.ensureReady()).thenReturn(catalog(snapshotPath));
        when(batchRepository.claimNextBatch(SUPPORTED_FROM, SUPPORTED_TO_EXCLUSIVE, BATCH_SIZE))
                .thenReturn(firstBatch, failingBatch);
        when(snapshotReader.read(snapshotPath)).thenReturn(snapshot);
        when(streetSegmentRepository.findAllById(List.of(10L)))
                .thenReturn(List.of(segment(10, "First Street", firstLine)));
        when(streetSegmentRepository.findAllById(List.of(20L)))
                .thenReturn(List.of(segment(20, "Second Street", secondLine)));
        when(batchRepository.finalizeBatch(anyList()))
                .thenReturn(3)
                .thenThrow(new IllegalStateException("database unavailable"));
        when(batchRepository.releaseBatch(failingBatch)).thenReturn(2);
        when(batchRepository.processingCounts()).thenReturn(
                new OhsomeEnrichmentBatchRepository.ProcessingCounts(2, 0, 3, 0, 0));

        OhsomeV2EnrichmentService.DrainSummary summary = service.drainPending(BATCH_SIZE);

        assertThat(summary.completed()).isFalse();
        assertThat(summary.processingFailure()).isEqualTo("IllegalStateException");
        assertThat(summary.releasedEvents()).isEqualTo(2);
        assertThat(summary.batches()).isEqualTo(1);
        assertThat(summary.processedPairs()).isEqualTo(1);
        assertThat(summary.matchedPairs()).isEqualTo(1);
        assertThat(summary.updatedEvents()).isEqualTo(3);
        InOrder order = inOrder(batchRepository);
        order.verify(batchRepository).finalizeBatch(anyList());
        order.verify(batchRepository).finalizeBatch(anyList());
        order.verify(batchRepository).releaseBatch(failingBatch);
        verify(snapshotReader, times(1)).read(snapshotPath);
        verify(tileBuildService, times(1)).markDataChanged();
    }

    private void givenSupportedWorkRemains() {
        when(batchRepository.hasPendingWork()).thenReturn(true, true);
    }

    private OhsomeSnapshotCatalog catalog(Path path) {
        OhsomeCachedSnapshot cached = new OhsomeCachedSnapshot(
                JANUARY,
                Instant.ofEpochMilli(SUPPORTED_FROM),
                path,
                1,
                "sha256",
                new OhsomeSnapshotMetadata(1, "2.0", "1.1.0", "EPSG:4326"));
        return new OhsomeSnapshotCatalog(Map.of(JANUARY, cached));
    }

    private StreetSegment segment(long id, String name, LineString geometry) {
        StreetSegment segment = new StreetSegment();
        segment.setId(id);
        segment.setStreetName(name);
        segment.setGeometry(geometry);
        return segment;
    }

    private LineString eastWestLine(double latitude, double startLongitude, double endLongitude) {
        return line(new Coordinate(startLongitude, latitude), new Coordinate(endLongitude, latitude));
    }

    private LineString offsetNorth(LineString source, double meters) {
        double latitudeOffset = meters / 111_320.0;
        Coordinate[] coordinates = source.getCoordinates();
        for (Coordinate coordinate : coordinates) {
            coordinate.y += latitudeOffset;
        }
        return line(coordinates);
    }

    private LineString line(Coordinate... coordinates) {
        LineString line = GEOMETRY_FACTORY.createLineString(coordinates);
        line.setSRID(4326);
        return line;
    }

    private static long monthStart(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}

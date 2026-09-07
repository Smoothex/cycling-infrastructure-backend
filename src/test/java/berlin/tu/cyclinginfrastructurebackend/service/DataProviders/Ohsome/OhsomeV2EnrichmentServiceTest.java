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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyDouble;
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
    private static final OhsomeTile TILE = new OhsomeTile(132, 524);
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
        verify(batchRepository, never()).markInvalidPendingEvents();
        verify(batchRepository, never()).processingCounts();
        verifyNoInteractions(snapshotCache, snapshotReader, streetSegmentRepository, tileBuildService);
    }

    @Test
    void invalidEventsAreFinalizedWithoutOpeningTheCache() {
        givenSupportedWorkRemains();
        when(batchRepository.markInvalidPendingEvents()).thenReturn(5);
        when(batchRepository.claimNextBatch(0.1, BATCH_SIZE)).thenReturn(Optional.empty());

        var summary = service.drainPending(BATCH_SIZE);

        assertThat(summary.completed()).isTrue();
        assertThat(summary.invalidEvents()).isEqualTo(5);
        verify(tileBuildService).markDataChanged();
        verifyNoInteractions(snapshotCache, snapshotReader, streetSegmentRepository);
    }

    @Test
    void cacheFailureReleasesClaimedEventsAndStopsTheDrain() {
        givenSupportedWorkRemains();
        List<OhsomeWorkItem> items = List.of(new OhsomeWorkItem(10, JANUARY));
        when(batchRepository.claimNextBatch(0.1, BATCH_SIZE)).thenReturn(claim(items));
        when(snapshotCache.ensureSnapshot(TILE, JANUARY)).thenThrow(new OhsomeSnapshotCacheException(
                OhsomeSnapshotCacheException.FailureKind.LOCAL_IO, "cache unavailable"));
        when(batchRepository.releaseBatch(items)).thenReturn(7);
        when(batchRepository.processingCounts()).thenReturn(
                new OhsomeEnrichmentBatchRepository.ProcessingCounts(7, 0, 0, 0, 0));

        var summary = service.drainPending(BATCH_SIZE);

        assertThat(summary.completed()).isFalse();
        assertThat(summary.cacheFailure()).isEqualTo("LOCAL_IO");
        assertThat(summary.releasedEvents()).isEqualTo(7);
        verify(batchRepository).releaseBatch(items);
        verify(batchRepository, times(1)).claimNextBatch(anyDouble(), anyInt());
        verify(batchRepository, never()).finalizeBatch(anyList());
        verifyNoInteractions(snapshotReader, streetSegmentRepository, tileBuildService);
    }

    @Test
    void finalizesMatchedAmbiguousNoMatchAndErrorResultsInOneMappedBatch() throws Exception {
        givenSupportedWorkRemains();
        Path snapshotPath = Path.of("january.parquet");
        when(snapshotCache.ensureSnapshot(TILE, JANUARY)).thenReturn(cached(snapshotPath));

        LineString matchedLine = eastWestLine(52.40, 13.20, 13.201);
        LineString ambiguousLine = eastWestLine(52.401, 13.202, 13.203);
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
        when(batchRepository.claimNextBatch(0.1, BATCH_SIZE))
                .thenReturn(claim(workItems), Optional.empty());
        StreetSegment matched = segment(10, "Matched Street", matchedLine);
        StreetSegment ambiguous = segment(20, null, ambiguousLine);
        StreetSegment unmatched = segment(30, null, eastWestLine(52.402, 13.204, 13.205));
        StreetSegment missingGeometry = segment(40, "Broken", null);
        when(streetSegmentRepository.findAllById(List.of(10L, 20L, 30L, 40L)))
                .thenReturn(List.of(matched, ambiguous, unmatched, missingGeometry));
        when(batchRepository.finalizeBatch(anyList())).thenReturn(9);
        when(batchRepository.markInvalidPendingEvents()).thenReturn(3);
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
        assertThat(summary.invalidEvents()).isEqualTo(3);
        verify(snapshotReader, times(1)).read(snapshotPath);
        verify(tileBuildService, times(1)).markDataChanged();
    }

    @Test
    void snapshotReadFailureReleasesTheClaimedWork() throws Exception {
        givenSupportedWorkRemains();
        Path snapshotPath = Path.of("corrupt-january.parquet");
        List<OhsomeWorkItem> workItems = List.of(new OhsomeWorkItem(10, JANUARY));
        when(snapshotCache.ensureSnapshot(TILE, JANUARY)).thenReturn(cached(snapshotPath));
        when(batchRepository.claimNextBatch(0.1, BATCH_SIZE))
                .thenReturn(claim(workItems));
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
        LineString secondLine = eastWestLine(52.401, 13.202, 13.203);
        OhsomeSnapshot snapshot = new OhsomeSnapshot(List.of(
                new OhsomeFeature(100, firstLine, Map.of("name", "First Street", "surface", "asphalt")),
                new OhsomeFeature(200, secondLine, Map.of("name", "Second Street", "surface", "paved"))));
        List<OhsomeWorkItem> firstBatch = List.of(new OhsomeWorkItem(10, JANUARY));
        List<OhsomeWorkItem> failingBatch = List.of(new OhsomeWorkItem(20, JANUARY));
        when(snapshotCache.ensureSnapshot(TILE, JANUARY)).thenReturn(cached(snapshotPath));
        when(batchRepository.claimNextBatch(0.1, BATCH_SIZE))
                .thenReturn(claim(firstBatch), claim(failingBatch));
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

    @Test
    void separateGermanCitiesUseTheirOwnSnapshotAndAllEventMonths() throws Exception {
        givenSupportedWorkRemains();
        // Include months on both sides of the former configured study period.
        OhsomeClaim berlin = cityClaim(10, new OhsomeTile(134, 525), YearMonth.of(2018, 12));
        OhsomeClaim hamburg = cityClaim(20, new OhsomeTile(99, 535), JANUARY);
        OhsomeClaim munich = cityClaim(30, new OhsomeTile(115, 481), YearMonth.of(2026, 2));
        when(batchRepository.claimNextBatch(0.1, BATCH_SIZE))
                .thenReturn(Optional.of(berlin), Optional.of(hamburg), Optional.of(munich), Optional.empty());
        List<LineString> lines = List.of(eastWestLine(52.52, 13.40, 13.401),
                eastWestLine(53.55, 9.99, 9.991), eastWestLine(48.13, 11.57, 11.571));
        List<OhsomeClaim> claims = List.of(berlin, hamburg, munich);
        for (int i = 0; i < claims.size(); i++) {
            OhsomeClaim claim = claims.get(i);
            long id = claim.items().getFirst().segmentId();
            Path path = Path.of(claim.tile().id(), claim.month() + ".parquet");
            when(snapshotCache.ensureSnapshot(claim.tile(), claim.month())).thenReturn(cached(path));
            when(snapshotReader.read(path)).thenReturn(new OhsomeSnapshot(List.of(
                    new OhsomeFeature(id, lines.get(i), Map.of("surface", "asphalt")))));
            when(streetSegmentRepository.findAllById(List.of(id)))
                    .thenReturn(List.of(segment(id, null, lines.get(i))));
        }
        when(batchRepository.finalizeBatch(anyList())).thenReturn(1);

        var summary = service.drainPending(BATCH_SIZE);

        assertThat(summary.matchedPairs()).isEqualTo(3);
        assertThat(summary.loadedSnapshots()).isEqualTo(3);
        assertThat(summary.completed()).isTrue();
        for (OhsomeClaim claim : claims) {
            verify(snapshotCache).ensureSnapshot(claim.tile(), claim.month());
        }
        verify(batchRepository, never()).releaseBatch(anyList());
    }

    @Test
    void permanentlyRejectedTileDoesNotBlockAnotherCity() throws Exception {
        givenSupportedWorkRemains();
        OhsomeClaim rejected = cityClaim(10, TILE, JANUARY);
        OhsomeClaim other = cityClaim(20, new OhsomeTile(99, 535), JANUARY);
        when(batchRepository.claimNextBatch(0.1, BATCH_SIZE))
                .thenReturn(Optional.of(rejected), Optional.of(other), Optional.empty());
        when(snapshotCache.ensureSnapshot(TILE, JANUARY)).thenThrow(new OhsomeSnapshotCacheException(
                OhsomeSnapshotCacheException.FailureKind.INVALID_REQUEST, "rejected"));
        Path path = Path.of("hamburg.parquet");
        when(snapshotCache.ensureSnapshot(other.tile(), JANUARY)).thenReturn(cached(path));
        when(snapshotReader.read(path)).thenReturn(new OhsomeSnapshot(List.of()));
        when(streetSegmentRepository.findAllById(List.of(20L)))
                .thenReturn(List.of(segment(20, null, eastWestLine(53.55, 9.99, 9.991))));
        when(batchRepository.finalizeBatch(anyList())).thenReturn(1);

        var summary = service.drainPending(BATCH_SIZE);

        verify(batchRepository).finalizeBatch(List.of(OhsomeBatchResult.error(rejected.items().getFirst())));
        verify(batchRepository).finalizeBatch(List.of(OhsomeBatchResult.noData(other.items().getFirst())));
        assertThat(summary.errorPairs()).isEqualTo(1);
        assertThat(summary.unmatchedPairs()).isEqualTo(1);
        assertThat(summary.completed()).isTrue();
    }

    private OhsomeClaim cityClaim(long id, OhsomeTile tile, YearMonth month) {
        return new OhsomeClaim(tile, month, List.of(new OhsomeWorkItem(id, month)));
    }

    private Optional<OhsomeClaim> claim(List<OhsomeWorkItem> items) {
        return Optional.of(new OhsomeClaim(TILE, JANUARY, items));
    }

    private void givenSupportedWorkRemains() {
        when(batchRepository.hasPendingWork()).thenReturn(true, true);
    }

    private OhsomeCachedSnapshot cached(Path path) {
        return new OhsomeCachedSnapshot(
                JANUARY,
                Instant.ofEpochMilli(SUPPORTED_FROM),
                path,
                1,
                "sha256",
                new OhsomeSnapshotMetadata(1, "2.0", "1.1.0", "EPSG:4326"));
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

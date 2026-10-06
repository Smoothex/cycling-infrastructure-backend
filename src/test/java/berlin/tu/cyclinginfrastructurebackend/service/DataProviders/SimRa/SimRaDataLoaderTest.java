package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.SimRa;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.service.DetourAnalysisResult;
import berlin.tu.cyclinginfrastructurebackend.service.DetourAnalysisService;
import berlin.tu.cyclinginfrastructurebackend.service.MapMatchingService;
import berlin.tu.cyclinginfrastructurebackend.service.ParsedRide;
import berlin.tu.cyclinginfrastructurebackend.service.PipelineActivityTracker;
import berlin.tu.cyclinginfrastructurebackend.service.RideFinalizationService;
import berlin.tu.cyclinginfrastructurebackend.service.RideProcessingResult;
import berlin.tu.cyclinginfrastructurebackend.service.RideTracePoint;
import berlin.tu.cyclinginfrastructurebackend.service.TileBuildService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimRaDataLoaderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void finalizesOnlyAfterInlineAnalysisSucceeds() throws Exception {
        Fixture fixture = fixture();
        when(fixture.detourAnalysisService.analyzeRide(fixture.ride, fixture.trace))
                .thenReturn(DetourAnalysisResult.empty());

        fixture.loader.importNextBatch();

        verify(fixture.rideFinalizationService).finalizeRide(
                fixture.ride, Map.of(42L, 1), DetourAnalysisResult.empty());
        verify(fixture.tileBuildService).markDataChanged();
    }

    @Test
    void detourFailureDoesNotInvokeFinalPersistence() throws Exception {
        Fixture fixture = fixture();
        when(fixture.detourAnalysisService.analyzeRide(fixture.ride, fixture.trace))
                .thenThrow(new IllegalStateException("forced detour failure"));

        fixture.loader.importNextBatch();

        verify(fixture.rideFinalizationService, never()).finalizeRide(any(), any(), any());
        verify(fixture.tileBuildService, never()).markDataChanged();
    }

    @Test
    void rescansAfterIdleDelayAndImportsNewFilesWithoutReimportingCommittedFiles() throws Exception {
        Fixture fixture = fixture();
        ReflectionTestUtils.setField(fixture.loader, "importBatchSize", 2);
        when(fixture.detourAnalysisService.analyzeRide(fixture.ride, fixture.trace))
                .thenReturn(DetourAnalysisResult.empty());

        fixture.loader.importNextBatch();
        when(fixture.rideRepository.findAllOriginalFilenames()).thenReturn(Set.of("VM_test.csv"));
        Files.writeString(tempDirectory.resolve("Rides/VM_new.csv"), "ignored");
        when(fixture.parser.parse(any(InputStream.class), eq("VM_new.csv")))
                .thenReturn(new ParsedRide(fixture.ride, fixture.trace));
        when(fixture.clock.millis()).thenReturn(59_999L);
        fixture.loader.importNextBatch();
        verify(fixture.rideRepository, times(1)).findAllOriginalFilenames();

        when(fixture.clock.millis()).thenReturn(60_000L);
        fixture.loader.importNextBatch();
        verify(fixture.rideRepository, times(2)).findAllOriginalFilenames();
        verify(fixture.parser, times(1)).parse(any(InputStream.class), eq("VM_test.csv"));
        verify(fixture.rideFinalizationService, times(2)).finalizeRide(
                fixture.ride, Map.of(42L, 1), DetourAnalysisResult.empty());

        when(fixture.rideRepository.findAllOriginalFilenames())
                .thenReturn(Set.of("VM_test.csv", "VM_new.csv"));
        when(fixture.clock.millis()).thenReturn(3_660_000L);
        fixture.loader.importNextBatch();
        verify(fixture.parser, times(1)).parse(any(InputStream.class), eq("VM_test.csv"));
        verify(fixture.parser, times(1)).parse(any(InputStream.class), eq("VM_new.csv"));
    }

    @Test
    void discoversFilesAfterInitiallyEmptySource() throws Exception {
        Fixture fixture = fixture();
        Files.delete(tempDirectory.resolve("Rides/VM_test.csv"));
        fixture.loader.importNextBatch();

        Files.writeString(tempDirectory.resolve("Rides/VM_test.csv"), "ignored");
        when(fixture.detourAnalysisService.analyzeRide(fixture.ride, fixture.trace))
                .thenReturn(DetourAnalysisResult.empty());
        when(fixture.clock.millis()).thenReturn(60_000L);
        fixture.loader.importNextBatch();

        verify(fixture.rideFinalizationService, times(1)).finalizeRide(
                fixture.ride, Map.of(42L, 1), DetourAnalysisResult.empty());
    }

    @Test
    void doesNotRetryInvalidFiles() throws Exception {
        Fixture fixture = fixture();
        when(fixture.parser.parse(any(InputStream.class), eq("VM_test.csv")))
                .thenThrow(new IOException("file is empty"));
        assertNoRetry(fixture);
        verify(fixture.rideFinalizationService, never()).finalizeRide(any(), any(), any());
    }

    @Test
    void importsNewFilesWhileInvalidFilesRemainSkipped() throws Exception {
        Fixture fixture = fixture();
        ReflectionTestUtils.setField(fixture.loader, "importBatchSize", 2);
        when(fixture.parser.parse(any(InputStream.class), eq("VM_test.csv")))
                .thenThrow(new IOException("file is empty"));
        fixture.loader.importNextBatch();

        Files.writeString(tempDirectory.resolve("Rides/VM_new.csv"), "ignored");
        when(fixture.parser.parse(any(InputStream.class), eq("VM_new.csv")))
                .thenReturn(new ParsedRide(fixture.ride, fixture.trace));
        when(fixture.detourAnalysisService.analyzeRide(fixture.ride, fixture.trace))
                .thenReturn(DetourAnalysisResult.empty());
        when(fixture.clock.millis()).thenReturn(60_000L);
        fixture.loader.importNextBatch();

        verify(fixture.parser, times(1)).parse(any(InputStream.class), eq("VM_test.csv"));
        verify(fixture.rideFinalizationService).finalizeRide(
                fixture.ride, Map.of(42L, 1), DetourAnalysisResult.empty());
    }

    @Test
    void doesNotRetryValidationRejections() throws Exception {
        Fixture fixture = fixture();
        when(fixture.parser.parse(any(InputStream.class), eq("VM_test.csv")))
                .thenReturn(new ParsedRide(fixture.ride, List.of()));
        assertNoRetry(fixture);
        verify(fixture.rideFinalizationService, never()).finalizeRide(any(), any(), any());
    }

    @Test
    void doesNotRetryProcessingExceptions() throws Exception {
        Fixture fixture = fixture();
        when(fixture.detourAnalysisService.analyzeRide(fixture.ride, fixture.trace))
                .thenThrow(new IllegalStateException("forced detour failure"));
        assertNoRetry(fixture);
        verify(fixture.rideFinalizationService, never()).finalizeRide(any(), any(), any());
    }

    @Test
    void doesNotRetryUnsuccessfulMapMatching() throws Exception {
        Fixture fixture = fixture();
        when(fixture.mapMatchingService.processRide(fixture.ride, fixture.trace))
                .thenReturn(new RideProcessingResult(false, Map.of(), 10L, 2L, 0L, 0L));

        assertNoRetry(fixture);

        verify(fixture.rideFinalizationService, never()).finalizeRide(any(), any(), any());
    }

    @Test
    void freshLoaderCanAttemptPreviouslyFailedFile() throws Exception {
        Fixture original = fixture();
        when(original.parser.parse(any(InputStream.class), eq("VM_test.csv")))
                .thenThrow(new IOException("file is empty"));
        original.loader.importNextBatch();

        Fixture restarted = fixture();
        when(restarted.detourAnalysisService.analyzeRide(restarted.ride, restarted.trace))
                .thenReturn(DetourAnalysisResult.empty());
        restarted.loader.importNextBatch();

        verify(restarted.rideFinalizationService).finalizeRide(
                restarted.ride, Map.of(42L, 1), DetourAnalysisResult.empty());
    }

    @Test
    void freshLoaderSkipsPreviouslyCommittedFile() throws Exception {
        Fixture fixture = fixture();
        when(fixture.rideRepository.findAllOriginalFilenames()).thenReturn(Set.of("VM_test.csv"));

        fixture.loader.importNextBatch();
        when(fixture.clock.millis()).thenReturn(86_400_000L);
        fixture.loader.importNextBatch();

        verify(fixture.parser, never()).parse(any(InputStream.class), any());
        verify(fixture.rideFinalizationService, never()).finalizeRide(any(), any(), any());
    }

    private void assertNoRetry(Fixture fixture) throws Exception {
        ReflectionTestUtils.setField(fixture.loader, "importBatchSize", 2);
        fixture.loader.importNextBatch();
        when(fixture.clock.millis()).thenReturn(60_000L);
        fixture.loader.importNextBatch();
        when(fixture.clock.millis()).thenReturn(3_540_000L);
        fixture.loader.importNextBatch();
        verify(fixture.parser, times(1)).parse(any(InputStream.class), eq("VM_test.csv"));

        when(fixture.clock.millis()).thenReturn(3_600_000L);
        fixture.loader.importNextBatch();
        when(fixture.clock.millis()).thenReturn(86_400_000L);
        fixture.loader.importNextBatch();
        verify(fixture.parser, times(1)).parse(any(InputStream.class), eq("VM_test.csv"));
    }

    private Fixture fixture() throws Exception {
        Path ridesDirectory = Files.createDirectories(tempDirectory.resolve("Rides"));
        Path source = Files.writeString(ridesDirectory.resolve("VM_test.csv"), "ignored");
        RideRepository rideRepository = mock(RideRepository.class);
        SimRaFileParser parser = mock(SimRaFileParser.class);
        MapMatchingService mapMatchingService = mock(MapMatchingService.class);
        DetourAnalysisService detourAnalysisService = mock(DetourAnalysisService.class);
        RideFinalizationService rideFinalizationService = mock(RideFinalizationService.class);
        TileBuildService tileBuildService = mock(TileBuildService.class);
        Ride ride = new Ride();
        ride.setStatus(Status.PROCESSED);
        GeometryFactory geometryFactory = new GeometryFactory();
        List<RideTracePoint> trace = List.of(
                new RideTracePoint(
                        geometryFactory.createPoint(new Coordinate(13.4, 52.5)), 1_000L),
                new RideTracePoint(
                        geometryFactory.createPoint(new Coordinate(13.41, 52.5)), 2_000L));
        when(rideRepository.findAllOriginalFilenames()).thenReturn(Set.of());
        when(parser.parse(any(InputStream.class), eq(source.getFileName().toString())))
                .thenReturn(new ParsedRide(ride, trace));
        when(mapMatchingService.canonicalizeTrace(trace)).thenReturn(trace);
        when(mapMatchingService.processRide(ride, trace)).thenReturn(new RideProcessingResult(
                true, Map.of(42L, 1), 10L, 2L, 3L, 4L));

        SimRaDataLoader loader = new SimRaDataLoader(
                rideRepository,
                parser,
                mapMatchingService,
                detourAnalysisService,
                rideFinalizationService,
                new PipelineActivityTracker(),
                tileBuildService);
        ReflectionTestUtils.setField(loader, "dataPath", tempDirectory.toString());
        ReflectionTestUtils.setField(loader, "isImportEnabled", true);
        ReflectionTestUtils.setField(loader, "pipelineEnabled", true);
        ReflectionTestUtils.setField(loader, "importBatchSize", 1);
        ReflectionTestUtils.setField(loader, "importThreadPoolSize", 1);
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(0L);
        ReflectionTestUtils.setField(loader, "clock", clock);
        return new Fixture(
                loader,
                rideRepository,
                ride,
                trace,
                detourAnalysisService,
                rideFinalizationService,
                tileBuildService,
                parser,
                clock,
                mapMatchingService);
    }

    private record Fixture(
            SimRaDataLoader loader,
            RideRepository rideRepository,
            Ride ride,
            List<RideTracePoint> trace,
            DetourAnalysisService detourAnalysisService,
            RideFinalizationService rideFinalizationService,
            TileBuildService tileBuildService,
            SimRaFileParser parser,
            Clock clock,
            MapMatchingService mapMatchingService
    ) {
    }
}

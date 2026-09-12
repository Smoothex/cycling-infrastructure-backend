package berlin.tu.cyclinginfrastructurebackend.service.DataProviders;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.EnrichmentStatus;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentEventRepository;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeV2EnrichmentService;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo.OpenMeteoBulkEnrichmentService;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo.OpenMeteoProperties;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.VIZ.RoadClosures.RoadClosureDataProvider;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.VIZ.Traffic.TrafficDataProvider;
import berlin.tu.cyclinginfrastructurebackend.service.PipelineActivityTracker;
import berlin.tu.cyclinginfrastructurebackend.service.PipelineWorkClaimService;
import berlin.tu.cyclinginfrastructurebackend.service.TileBuildService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class ExternalFactorEnrichmentSchedulerTest {

    @ParameterizedTest
    @CsvSource({"true,true,false", "true,false,true", "false,true,true"})
    void disabledVizJobsDoNotClaimEventsOrInitializeProviders(boolean pipeline, boolean enrichment, boolean viz) {
        SegmentEventRepository repository = mock(SegmentEventRepository.class);
        RoadClosureDataProvider closures = mock(RoadClosureDataProvider.class);
        TrafficDataProvider traffic = mock(TrafficDataProvider.class);
        PipelineWorkClaimService claims = mock(PipelineWorkClaimService.class);
        ExternalFactorEnrichmentScheduler scheduler = new ExternalFactorEnrichmentScheduler(
                repository, mock(OpenMeteoBulkEnrichmentService.class), new OpenMeteoProperties(),
                closures, mock(OhsomeV2EnrichmentService.class), traffic, claims,
                mock(TileBuildService.class), new PipelineActivityTracker());
        ReflectionTestUtils.setField(scheduler, "pipelineEnabled", pipeline);
        ReflectionTestUtils.setField(scheduler, "enrichmentEnabled", enrichment);
        ReflectionTestUtils.setField(scheduler, "berlinOpenDataEnabled", viz);
        ReflectionTestUtils.setField(scheduler, "trafficEnabled", viz);

        scheduler.enrichBerlinOpenDataPending();
        scheduler.enrichTrafficPending();

        verifyNoInteractions(repository, closures, traffic, claims);
    }

    @Test
    void weatherEnrichmentInvokesExactlyOneBulkBatch() {
        OpenMeteoBulkEnrichmentService weatherService = mock(OpenMeteoBulkEnrichmentService.class);
        ExternalFactorEnrichmentScheduler scheduler = new ExternalFactorEnrichmentScheduler(
                mock(SegmentEventRepository.class),
                weatherService,
                new OpenMeteoProperties(),
                mock(RoadClosureDataProvider.class),
                mock(OhsomeV2EnrichmentService.class),
                mock(TrafficDataProvider.class),
                mock(PipelineWorkClaimService.class),
                mock(TileBuildService.class),
                new PipelineActivityTracker()
        );
        ReflectionTestUtils.setField(scheduler, "pipelineEnabled", true);
        ReflectionTestUtils.setField(scheduler, "enrichmentEnabled", true);
        ReflectionTestUtils.setField(scheduler, "weatherEnabled", true);

        scheduler.enrichWeatherPending();

        verify(weatherService).processNextBatch();
    }

    @Test
    void weatherDatabaseTimeoutPausesTheNextScheduledAttempt() {
        OpenMeteoBulkEnrichmentService weatherService = mock(OpenMeteoBulkEnrichmentService.class);
        doThrow(new QueryTimeoutException("timeout", new RuntimeException("statement timeout")))
                .when(weatherService).processNextBatch();
        ExternalFactorEnrichmentScheduler scheduler = new ExternalFactorEnrichmentScheduler(
                mock(SegmentEventRepository.class),
                weatherService,
                new OpenMeteoProperties(),
                mock(RoadClosureDataProvider.class),
                mock(OhsomeV2EnrichmentService.class),
                mock(TrafficDataProvider.class),
                mock(PipelineWorkClaimService.class),
                mock(TileBuildService.class),
                new PipelineActivityTracker()
        );
        ReflectionTestUtils.setField(scheduler, "pipelineEnabled", true);
        ReflectionTestUtils.setField(scheduler, "enrichmentEnabled", true);
        ReflectionTestUtils.setField(scheduler, "weatherEnabled", true);

        scheduler.enrichWeatherPending();
        scheduler.enrichWeatherPending();

        verify(weatherService, times(1)).processNextBatch();
    }

    @Test
    void roadDisruptionEnrichmentUsesTheExactEventTimestamp() {
        SegmentEventRepository eventRepository = mock(SegmentEventRepository.class);
        RoadClosureDataProvider roadClosureDataProvider = mock(RoadClosureDataProvider.class);
        PipelineWorkClaimService workClaimService = mock(PipelineWorkClaimService.class);
        TileBuildService tileBuildService = mock(TileBuildService.class);

        ExternalFactorEnrichmentScheduler scheduler = new ExternalFactorEnrichmentScheduler(
                eventRepository,
                mock(OpenMeteoBulkEnrichmentService.class),
                new OpenMeteoProperties(),
                roadClosureDataProvider,
                mock(OhsomeV2EnrichmentService.class),
                mock(TrafficDataProvider.class),
                workClaimService,
                tileBuildService,
                new PipelineActivityTracker()
        );
        ReflectionTestUtils.setField(scheduler, "pipelineEnabled", true);
        ReflectionTestUtils.setField(scheduler, "enrichmentEnabled", true);
        ReflectionTestUtils.setField(scheduler, "berlinOpenDataEnabled", true);
        ReflectionTestUtils.setField(scheduler, "berlinOpenDataBatchSize", 10);

        UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        StreetSegment segment = new StreetSegment();
        segment.setId(42L);
        SegmentEvent event = new SegmentEvent();
        event.setId(eventId);
        event.setSegment(segment);
        event.setEventTimestamp(1_731_000_123_456L);

        when(workClaimService.claimBerlinOpenDataEvents(10)).thenReturn(List.of(eventId));
        when(eventRepository.findWithSegmentByIdIn(List.of(eventId))).thenReturn(List.of(event));

        scheduler.enrichBerlinOpenDataPending();

        verify(roadClosureDataProvider).enrichSegment(
                segment,
                event.getEventTimestamp(),
                event.getEventTimestamp()
        );
        verify(eventRepository).markBerlinOpenDataEnriched(eventId, EnrichmentStatus.DONE);
        verify(tileBuildService).markDataChanged();
    }
}

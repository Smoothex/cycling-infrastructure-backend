package berlin.tu.cyclinginfrastructurebackend.service.DataProviders;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.EnrichmentStatus;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentEventRepository;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome.OhsomeV2EnrichmentService;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo.WeatherDataProvider;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.VIZ.RoadClosures.RoadClosureDataProvider;
import berlin.tu.cyclinginfrastructurebackend.service.DataProviders.VIZ.Traffic.TrafficDataProvider;
import berlin.tu.cyclinginfrastructurebackend.service.PipelineActivityTracker;
import berlin.tu.cyclinginfrastructurebackend.service.PipelineWorkClaimService;
import berlin.tu.cyclinginfrastructurebackend.service.TileBuildService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalFactorEnrichmentSchedulerTest {

    @Test
    void roadDisruptionEnrichmentUsesTheExactEventTimestamp() {
        SegmentEventRepository eventRepository = mock(SegmentEventRepository.class);
        RoadClosureDataProvider roadClosureDataProvider = mock(RoadClosureDataProvider.class);
        PipelineWorkClaimService workClaimService = mock(PipelineWorkClaimService.class);
        TileBuildService tileBuildService = mock(TileBuildService.class);

        ExternalFactorEnrichmentScheduler scheduler = new ExternalFactorEnrichmentScheduler(
                eventRepository,
                mock(WeatherDataProvider.class),
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

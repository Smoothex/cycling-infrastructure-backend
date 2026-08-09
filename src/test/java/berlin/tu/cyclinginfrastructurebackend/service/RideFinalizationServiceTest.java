package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEventType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import berlin.tu.cyclinginfrastructurebackend.repository.RideRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentEventRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RideFinalizationServiceTest {

    private final RideRepository rideRepository = mock(RideRepository.class);
    private final StreetSegmentRepository segmentRepository = mock(StreetSegmentRepository.class);
    private final SegmentEventRepository eventRepository = mock(SegmentEventRepository.class);
    private final RideFinalizationService service = new RideFinalizationService(
            rideRepository, segmentRepository, eventRepository);

    @Test
    void flushesRideThenLocksOneSortedUnionAndAppliesAllPreparedWrites() {
        Ride ride = finalizedRide();
        StreetSegment segment12 = segment(12L);
        StreetSegment segment42 = segment(42L);
        when(rideRepository.saveAndFlush(ride)).thenReturn(ride);
        when(segmentRepository.lockForUpdate(List.of(12L, 42L))).thenReturn(List.of(12L, 42L));
        when(segmentRepository.incrementUsageCounts(Map.of(42L, 2))).thenReturn(1);
        when(segmentRepository.incrementAvoidanceAll(List.of(12L))).thenReturn(1);
        when(segmentRepository.incrementPreferenceAll(List.of(42L))).thenReturn(1);
        when(segmentRepository.getReferenceById(12L)).thenReturn(segment12);
        when(segmentRepository.getReferenceById(42L)).thenReturn(segment42);
        DetourAnalysisResult result = new DetourAnalysisResult(
                Map.of(12, 180.0), Map.of(12, 1_100L),
                Map.of(42, 90.0), Map.of(42, 1_900L));

        service.finalizeRide(ride, Map.of(42L, 2), result);

        InOrder order = inOrder(rideRepository, segmentRepository, eventRepository);
        order.verify(rideRepository).saveAndFlush(ride);
        order.verify(segmentRepository).lockForUpdate(List.of(12L, 42L));
        order.verify(segmentRepository).incrementUsageCounts(Map.of(42L, 2));
        order.verify(segmentRepository).incrementAvoidanceAll(List.of(12L));
        order.verify(segmentRepository).incrementPreferenceAll(List.of(42L));
        order.verify(eventRepository).saveAll(anyList());

        ArgumentCaptor<List<SegmentEvent>> events = ArgumentCaptor.forClass(List.class);
        verify(eventRepository).saveAll(events.capture());
        assertThat(events.getValue()).extracting(SegmentEvent::getEventType)
                .containsExactly(SegmentEventType.AVOIDANCE, SegmentEventType.PREFERENCE);
        assertThat(events.getValue()).allSatisfy(event -> assertThat(event.getRide()).isSameAs(ride));
    }

    @Test
    void rejectsEveryNonFinalRideBeforeWriting() {
        for (Status status : List.of(Status.PENDING, Status.ANALYZING, Status.ERROR)) {
            Ride ride = new Ride();
            ride.setStatus(status);
            assertThatThrownBy(() -> service.finalizeRide(
                    ride, Map.of(), DetourAnalysisResult.empty()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(status.name());
        }
        verifyNoInteractions(rideRepository, segmentRepository, eventRepository);
    }

    @Test
    void counterMismatchFailsBeforeEventsAreSaved() {
        Ride ride = finalizedRide();
        when(rideRepository.saveAndFlush(ride)).thenReturn(ride);
        when(segmentRepository.lockForUpdate(List.of(42L))).thenReturn(List.of(42L));
        when(segmentRepository.incrementUsageCounts(Map.of(42L, 2))).thenReturn(0);

        assertThatThrownBy(() -> service.finalizeRide(
                ride, Map.of(42L, 2), DetourAnalysisResult.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("usage");

        verify(eventRepository, never()).saveAll(anyList());
    }

    @Test
    void finalizationFailureMarksTheTransactionForRollback() {
        Ride ride = finalizedRide();
        when(rideRepository.saveAndFlush(ride)).thenReturn(ride);
        when(segmentRepository.lockForUpdate(List.of(42L))).thenReturn(List.of(42L));
        when(segmentRepository.incrementUsageCounts(Map.of(42L, 1)))
                .thenThrow(new IllegalStateException("forced failure"));
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(org.mockito.ArgumentMatchers.any()))
                .thenReturn(transactionStatus);
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        RideFinalizationService transactionalService = (RideFinalizationService) proxyFactory.getProxy();

        assertThatThrownBy(() -> transactionalService.finalizeRide(
                ride, Map.of(42L, 1), DetourAnalysisResult.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("forced failure");

        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(transactionStatus);
        verify(eventRepository, never()).saveAll(anyList());
    }

    private Ride finalizedRide() {
        Ride ride = new Ride();
        ride.setStatus(Status.PROCESSED);
        ride.setStartTime(1_000L);
        return ride;
    }

    private StreetSegment segment(long id) {
        StreetSegment segment = new StreetSegment();
        segment.setId(id);
        return segment;
    }
}

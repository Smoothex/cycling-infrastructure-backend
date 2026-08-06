package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.repository.SegmentEventRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import com.graphhopper.GraphHopper;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.PointList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StreetSegmentServiceTest {

    @Test
    void createsMissingSegmentsInRequiresNewTransaction() {
        StreetSegmentRepository repository = mock(StreetSegmentRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(repository.findExistingIds(List.of(42L))).thenReturn(List.of());

        GraphHopperService graphHopperService = mock(GraphHopperService.class);
        GraphHopper graphHopper = mock(GraphHopper.class);
        BaseGraph baseGraph = mock(BaseGraph.class);
        EdgeIteratorState edge = mock(EdgeIteratorState.class);
        PointList geometry = new PointList();
        geometry.add(52.5, 13.4);
        geometry.add(52.51, 13.41);
        when(graphHopperService.getHopper()).thenReturn(graphHopper);
        when(graphHopper.getBaseGraph()).thenReturn(baseGraph);
        when(baseGraph.getEdgeIteratorState(42, Integer.MIN_VALUE)).thenReturn(edge);
        when(edge.getName()).thenReturn("Teststraße");
        when(edge.fetchWayGeometry(FetchMode.ALL)).thenReturn(geometry);
        when(graphHopperService.getGradientPercent(42)).thenReturn(1.5);

        StreetSegmentService service = new StreetSegmentService(
                repository, mock(SegmentEventRepository.class), transactionManager);

        service.ensureSegmentsExist(List.of(42), graphHopperService);

        ArgumentCaptor<TransactionDefinition> transactionDefinition =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(transactionDefinition.capture());
        assertThat(transactionDefinition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(repository).upsertSegment(eq(42L), eq("Teststraße"), any(), eq(1.5));
        verify(transactionManager).commit(transactionStatus);
    }
}

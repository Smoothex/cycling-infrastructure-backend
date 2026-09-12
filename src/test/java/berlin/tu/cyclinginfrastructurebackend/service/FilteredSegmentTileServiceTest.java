package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEnrichmentFilter;
import berlin.tu.cyclinginfrastructurebackend.repository.FilteredSegmentTileRepository;
import berlin.tu.cyclinginfrastructurebackend.service.dto.SegmentTileFilter;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FilteredSegmentTileServiceTest {
    private final FilteredSegmentTileRepository repository = mock(FilteredSegmentTileRepository.class);
    private final TileBuildService builds = mock(TileBuildService.class);
    private final FilteredSegmentTileService service = new FilteredSegmentTileService(repository, builds);
    private final SegmentTileFilter filter = new SegmentTileFilter(0, Long.MAX_VALUE, null, null,
            Set.of(SegmentEnrichmentFilter.WEATHER_ENRICHED, SegmentEnrichmentFilter.OHSOME_ENRICHED));

    @Test
    void cachesByCompleteFilterAndInvalidatesWhenPipelineVersionChanges() {
        when(repository.tile(anyInt(), anyInt(), anyInt(), any())).thenReturn(new byte[]{1});
        service.tile(14, 8802, 5374, filter);
        service.tile(14, 8802, 5374, new SegmentTileFilter(0, Long.MAX_VALUE, null, null,
                Set.of(SegmentEnrichmentFilter.OHSOME_ENRICHED, SegmentEnrichmentFilter.WEATHER_ENRICHED)));
        verify(repository).tile(14, 8802, 5374, filter);
        when(builds.dataVersion()).thenReturn(1L);
        service.tile(14, 8802, 5374, filter);
        verify(repository, times(2)).tile(14, 8802, 5374, filter);
        var yearFilter = new SegmentTileFilter(100, 200, null, null, filter.enrichments());
        service.tile(14, 8802, 5374, yearFilter);
        verify(repository).tile(14, 8802, 5374, yearFilter);
    }

    @Test
    void deduplicatesConcurrentLoadsAndDoesNotCacheFailures() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.tile(14, 8802, 5374, filter)).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new byte[]{1, 2};
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.tile(14, 8802, 5374, filter));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.tile(14, 8802, 5374, filter));
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).containsExactly(1, 2);
            assertThat(second.get(5, TimeUnit.SECONDS)).containsExactly(1, 2);
        }
        verify(repository).tile(14, 8802, 5374, filter);
        when(repository.tile(14, 8803, 5374, filter)).thenThrow(new IllegalStateException("temporary"))
                .thenReturn(new byte[]{3});
        assertThatThrownBy(() -> service.tile(14, 8803, 5374, filter)).isInstanceOf(IllegalStateException.class);
        assertThat(service.tile(14, 8803, 5374, filter)).containsExactly(3);
    }

    @Test
    void boundsCacheEntryCount() {
        when(repository.tile(anyInt(), anyInt(), anyInt(), any())).thenReturn(new byte[]{1});
        for (int x = 0; x <= 256; x++) service.tile(14, x, 5374, filter);
        service.tile(14, 0, 5374, filter);
        verify(repository, times(2)).tile(14, 0, 5374, filter);
    }
}

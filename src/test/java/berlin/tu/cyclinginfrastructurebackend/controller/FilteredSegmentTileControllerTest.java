package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.service.FilteredSegmentTileService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FilteredSegmentTileControllerTest {
    private final FilteredSegmentTileService service = mock(FilteredSegmentTileService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FilteredSegmentTileController(service)).build();
    private static final String URL = "/api/segments/tiles/11/1100/671.mvt?enrichmentFilters=OHSOME_ENRICHED";

    @Test
    void revalidatesUnchangedTilesAndSendsChangedBytesAfterEnrichment() throws Exception {
        when(service.tile(anyInt(), anyInt(), anyInt(), any())).thenReturn(new byte[]{1, 2});
        var response = mvc.perform(get(URL)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=30, private"))
                .andExpect(content().bytes(new byte[]{1, 2})).andReturn().getResponse();
        String etag = response.getHeader("ETag");
        mvc.perform(get(URL).header("If-None-Match", etag))
                .andExpect(status().isNotModified()).andExpect(content().bytes(new byte[0]));
        when(service.tile(anyInt(), anyInt(), anyInt(), any())).thenReturn(new byte[]{3});
        mvc.perform(get(URL).header("If-None-Match", etag))
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[]{3}));
    }

    @Test
    void emptyTilesRemainSuccessfulCacheableResponses() throws Exception {
        when(service.tile(anyInt(), anyInt(), anyInt(), any())).thenReturn(new byte[0]);
        mvc.perform(get(URL)).andExpect(status().isOk()).andExpect(header().exists("ETag"))
                .andExpect(content().bytes(new byte[0]));
    }
}

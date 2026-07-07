package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.BikeType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEventType;
import berlin.tu.cyclinginfrastructurebackend.repository.IncidentRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentEventRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentExternalFactorRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.StreetSegmentRepository;
import berlin.tu.cyclinginfrastructurebackend.service.GeoJsonMapper;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.GeoJsonFeatureCollectionDto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SegmentControllerTest {

    private final StreetSegmentRepository segmentRepository = mock(StreetSegmentRepository.class);
    private final IncidentRepository incidentRepository = mock(IncidentRepository.class);
    private final SegmentExternalFactorRepository factorRepository = mock(SegmentExternalFactorRepository.class);
    private final SegmentEventRepository eventRepository = mock(SegmentEventRepository.class);
    private final GeoJsonMapper geoJsonMapper = mock(GeoJsonMapper.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new SegmentController(
                    segmentRepository,
                    incidentRepository,
                    factorRepository,
                    eventRepository,
                    geoJsonMapper
            ))
            .build();

    @Test
    void geoJsonEndpointReturnsFeatureCollection() throws Exception {
        when(segmentRepository.findSegmentsForMap(0.2, 0.2, 1, 1000)).thenReturn(List.of());
        when(geoJsonMapper.toSegmentFeatureCollection(eq(List.of()), any())).thenReturn(
                new GeoJsonFeatureCollectionDto("FeatureCollection", List.of())
        );

        mockMvc.perform(get("/api/segments/geojson"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("FeatureCollection"));
    }

    @Test
    void segmentEventsEndpointCapsLimit() throws Exception {
        when(segmentRepository.existsById(42L)).thenReturn(true);

        mockMvc.perform(get("/api/segments/42/events").param("limit", "5000"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(eventRepository).findSegmentEventsForApi(
                eq(42L),
                eq(null),
                eq(null),
                eq(null),
                pageableCaptor.capture()
        );
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(1000);
    }

    @Test
    void segmentEventsEndpointIncludesBikeTypeAndEnrichmentFlags() throws Exception {
        StreetSegment segment = new StreetSegment();
        segment.setId(42L);

        Ride ride = new Ride();
        ride.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        ride.setBikeType(BikeType.CITY_TREKKING_BIKE);

        SegmentEvent event = new SegmentEvent();
        event.setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        event.setSegment(segment);
        event.setRide(ride);
        event.setEventType(SegmentEventType.PREFERENCE);
        event.setEventTimestamp(123456789L);
        event.setWeatherEnriched(true);
        event.setOhsomeEnriched(true);
        event.setTrafficEnriched(true);

        when(segmentRepository.existsById(42L)).thenReturn(true);
        when(eventRepository.findSegmentEventsForApi(eq(42L), eq(null), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(List.of(event));

        mockMvc.perform(get("/api/segments/42/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bikeType").value("CITY_TREKKING_BIKE"))
                .andExpect(jsonPath("$[0].weatherEnriched").value(true))
                .andExpect(jsonPath("$[0].ohsomeEnriched").value(true))
                .andExpect(jsonPath("$[0].trafficEnriched").value(true));
    }
}

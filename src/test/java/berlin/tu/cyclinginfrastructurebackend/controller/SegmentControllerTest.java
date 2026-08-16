package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;
import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import berlin.tu.cyclinginfrastructurebackend.domain.SegmentExternalFactor;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.BikeType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ExternalFactorType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RideIntent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEventType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficCondition;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
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
        when(segmentRepository.findSegmentsForMap(
                0.2, 0.2, 1, false, 0L, Long.MAX_VALUE, false, false, false, false, false,
                "", "", 1000))
                .thenReturn(List.of());
        when(geoJsonMapper.toSegmentFeatureCollection(eq(List.of()), any())).thenReturn(
                new GeoJsonFeatureCollectionDto("FeatureCollection", List.of())
        );

        mockMvc.perform(get("/api/segments/geojson"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("FeatureCollection"));
    }

    @Test
    void geoJsonEndpointForwardsCombinedFilters() throws Exception {
        when(segmentRepository.findSegmentsForMap(
                0.2, 0.2, 1, true, 100L, 200L,
                true, false, false, true, true,
                "COMMUTE", "HEAVY", 1000
        )).thenReturn(List.of());
        when(geoJsonMapper.toSegmentFeatureCollection(eq(List.of()), any())).thenReturn(
                new GeoJsonFeatureCollectionDto("FeatureCollection", List.of())
        );

        mockMvc.perform(get("/api/segments/geojson")
                        .param("from", "100")
                        .param("to", "200")
                        .param("rideIntent", "COMMUTE")
                        .param("trafficCondition", "HEAVY")
                        .param("enrichmentFilters", "WEATHER_ENRICHED", "TRAFFIC_MEASURED", "ROAD_DISRUPTION_AFFECTED"))
                .andExpect(status().isOk());

        verify(segmentRepository).findSegmentsForMap(
                0.2, 0.2, 1, true, 100L, 200L,
                true, false, false, true, true,
                "COMMUTE", "HEAVY", 1000
        );
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
                eq(false),
                eq(false),
                eq(false),
                eq(false),
                eq(false),
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
        when(eventRepository.findSegmentEventsForApi(
                eq(42L), eq(null), eq(null), eq(null),
                eq(false), eq(false), eq(false), eq(false), eq(false),
                eq(null), eq(null), any(Pageable.class)))
                .thenReturn(List.of(event));

        mockMvc.perform(get("/api/segments/42/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bikeType").value("CITY_TREKKING_BIKE"))
                .andExpect(jsonPath("$[0].rideId").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$[0].weatherEnriched").value(true))
                .andExpect(jsonPath("$[0].ohsomeEnriched").value(true))
                .andExpect(jsonPath("$[0].trafficEnriched").value(true))
                .andExpect(jsonPath("$[0].roadDisruptions").isEmpty());
    }

    @Test
    void segmentEventsEndpointAttachesAllDisruptionsActiveAtEachExactTimestamp() throws Exception {
        StreetSegment segment = new StreetSegment();
        segment.setId(42L);

        Ride ride = new Ride();
        ride.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

        SegmentEvent firstEvent = event(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                segment,
                ride,
                2_000L
        );
        SegmentEvent secondEvent = event(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                segment,
                ride,
                3_000L
        );

        SegmentExternalFactor ongoingConstruction = disruption(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                segment,
                ExternalFactorType.CONSTRUCTION,
                1_500L,
                null,
                "construction-1"
        );
        SegmentExternalFactor boundaryClosure = disruption(
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                segment,
                ExternalFactorType.ROAD_CLOSURE,
                2_000L,
                2_000L,
                "closure-1"
        );
        SegmentExternalFactor laterHazard = disruption(
                UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                segment,
                ExternalFactorType.HAZARD,
                3_000L,
                3_500L,
                "hazard-1"
        );

        when(segmentRepository.existsById(42L)).thenReturn(true);
        when(eventRepository.findSegmentEventsForApi(
                eq(42L), eq(null), eq(null), eq(null),
                eq(false), eq(false), eq(false), eq(false), eq(false),
                eq(null), eq(null), any(Pageable.class)))
                .thenReturn(List.of(firstEvent, secondEvent));
        when(factorRepository.findDisruptionsOverlapping(
                eq(42L), eq("berlin-open-data"), anyCollection(), eq(2_000L), eq(3_000L)))
                .thenReturn(List.of(laterHazard, boundaryClosure, ongoingConstruction));

        mockMvc.perform(get("/api/segments/42/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roadDisruptions.length()").value(2))
                .andExpect(jsonPath("$[0].roadDisruptions[0].factorType").value("CONSTRUCTION"))
                .andExpect(jsonPath("$[0].roadDisruptions[1].factorType").value("ROAD_CLOSURE"))
                .andExpect(jsonPath("$[0].roadDisruptions[1].metadata.id").value("closure-1"))
                .andExpect(jsonPath("$[1].roadDisruptions.length()").value(2))
                .andExpect(jsonPath("$[1].roadDisruptions[0].factorType").value("CONSTRUCTION"))
                .andExpect(jsonPath("$[1].roadDisruptions[1].factorType").value("HAZARD"));

        verify(factorRepository).findDisruptionsOverlapping(
                eq(42L),
                eq("berlin-open-data"),
                eq(Set.of(
                        ExternalFactorType.CONSTRUCTION,
                        ExternalFactorType.ROAD_CLOSURE,
                        ExternalFactorType.EVENT,
                        ExternalFactorType.HAZARD,
                        ExternalFactorType.INCIDENT
                )),
                eq(2_000L),
                eq(3_000L)
        );
    }

    @Test
    void suspiciousSegmentsEndpointForwardsCombinedFilters() throws Exception {
        when(segmentRepository.findSuspiciousSegments(
                0.2, 10, true, 100L, 200L,
                true, false, false, true, true,
                "COMMUTE", "HEAVY", 50
        )).thenReturn(List.of());

        mockMvc.perform(get("/api/segments")
                        .param("from", "100")
                        .param("to", "200")
                        .param("rideIntent", "COMMUTE")
                        .param("trafficCondition", "HEAVY")
                        .param("enrichmentFilters", "WEATHER_ENRICHED", "TRAFFIC_MEASURED", "ROAD_DISRUPTION_AFFECTED"))
                .andExpect(status().isOk());

        verify(segmentRepository).findSuspiciousSegments(
                0.2, 10, true, 100L, 200L,
                true, false, false, true, true,
                "COMMUTE", "HEAVY", 50
        );
    }

    @Test
    void segmentEventsEndpointForwardsRideIntentTrafficConditionAndRoadDisruptionFilter() throws Exception {
        when(segmentRepository.existsById(42L)).thenReturn(true);

        mockMvc.perform(get("/api/segments/42/events")
                        .param("rideIntent", "COMMUTE")
                        .param("trafficCondition", "HEAVY")
                        .param("enrichmentFilters", "ROAD_DISRUPTION_AFFECTED"))
                .andExpect(status().isOk());

        verify(eventRepository).findSegmentEventsForApi(
                eq(42L), eq(null), eq(null), eq(null),
                eq(false), eq(false), eq(false), eq(false), eq(true),
                eq(RideIntent.COMMUTE), eq(TrafficCondition.HEAVY), any(Pageable.class)
        );
    }

    private SegmentEvent event(UUID id,
                               StreetSegment segment,
                               Ride ride,
                               long timestamp) {
        SegmentEvent event = new SegmentEvent();
        event.setId(id);
        event.setSegment(segment);
        event.setRide(ride);
        event.setEventType(SegmentEventType.PREFERENCE);
        event.setEventTimestamp(timestamp);
        return event;
    }

    private SegmentExternalFactor disruption(UUID id,
                                             StreetSegment segment,
                                             ExternalFactorType factorType,
                                             long validFrom,
                                             Long validTo,
                                             String externalId) {
        SegmentExternalFactor factor = new SegmentExternalFactor();
        factor.setId(id);
        factor.setSegment(segment);
        factor.setFactorType(factorType);
        factor.setSource("berlin-open-data");
        factor.setValidFrom(validFrom);
        factor.setValidTo(validTo);
        factor.setMetadata(Map.of("id", externalId, "severity", "FULL_CLOSURE"));
        return factor;
    }
}

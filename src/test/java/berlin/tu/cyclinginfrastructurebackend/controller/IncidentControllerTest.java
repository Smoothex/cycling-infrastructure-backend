package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.domain.Incident;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.IncidentType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ParticipantType;
import berlin.tu.cyclinginfrastructurebackend.repository.IncidentRepository;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IncidentControllerTest {

    private final IncidentRepository incidentRepository = mock(IncidentRepository.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new IncidentController(incidentRepository))
            .build();

    @Test
    void nearMissesEndpointReturnsIncidentPoints() throws Exception {
        GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

        Incident incident = new Incident();
        incident.setId(UUID.randomUUID());
        incident.setLocation(geometryFactory.createPoint(new Coordinate(13.4, 52.55)));
        incident.setTimestamp(1719871200000L);
        incident.setIncidentType(IncidentType.CLOSE_PASS);
        incident.setScary(true);
        incident.setDescription("Car overtook with no distance");
        incident.setInvolvedParticipants(Set.of(ParticipantType.CAR));

        when(incidentRepository.findNearMisses(isNull(), isNull())).thenReturn(List.of(incident));

        mockMvc.perform(get("/api/incidents/near-misses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lon").value(13.4))
                .andExpect(jsonPath("$[0].lat").value(52.55))
                .andExpect(jsonPath("$[0].timestamp").value(1719871200000L))
                .andExpect(jsonPath("$[0].incidentType").value("CLOSE_PASS"))
                .andExpect(jsonPath("$[0].scary").value(true))
                .andExpect(jsonPath("$[0].description").value("Car overtook with no distance"))
                .andExpect(jsonPath("$[0].involvedParticipants[0]").value("CAR"));
    }

    @Test
    void nearMissesEndpointPassesTimeBoundsToRepository() throws Exception {
        when(incidentRepository.findNearMisses(eq(1704067200000L), eq(1735689599999L)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/incidents/near-misses")
                        .param("from", "1704067200000")
                        .param("to", "1735689599999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}

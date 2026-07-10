package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.domain.TrafficDetector;
import berlin.tu.cyclinginfrastructurebackend.repository.TrafficDetectorRepository;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TrafficDetectorControllerTest {

    private final TrafficDetectorRepository detectorRepository = mock(TrafficDetectorRepository.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new TrafficDetectorController(detectorRepository, 75.0))
            .build();

    @Test
    void detectorsEndpointReturnsPositionsAndMatchRadius() throws Exception {
        GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

        TrafficDetector located = new TrafficDetector();
        located.setDetNameAlt("TE001");
        located.setStreet("Bornholmer Straße");
        located.setDirection("Nord");
        located.setDeinstalled(false);
        located.setLocation(geometryFactory.createPoint(new Coordinate(13.4, 52.55)));

        TrafficDetector unlocated = new TrafficDetector();
        unlocated.setDetNameAlt("TE002");

        when(detectorRepository.findAll()).thenReturn(List.of(located, unlocated));

        mockMvc.perform(get("/api/traffic/detectors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchRadiusMeters").value(75.0))
                .andExpect(jsonPath("$.detectors.length()").value(1))
                .andExpect(jsonPath("$.detectors[0].detName").value("TE001"))
                .andExpect(jsonPath("$.detectors[0].lon").value(13.4))
                .andExpect(jsonPath("$.detectors[0].lat").value(52.55));
    }
}

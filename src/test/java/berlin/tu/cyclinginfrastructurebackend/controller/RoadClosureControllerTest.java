package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.domain.RoadClosure;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ExternalFactorType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RoadClosureSeverity;
import berlin.tu.cyclinginfrastructurebackend.repository.RoadClosureRepository;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RoadClosureControllerTest {

    private final RoadClosureRepository roadClosureRepository = mock(RoadClosureRepository.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new RoadClosureController(roadClosureRepository))
            .build();
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void roadClosuresEndpointSplitsGeometryIntoLabelPointAndLines() throws Exception {
        Geometry collection = geometryFactory.createGeometryCollection(new Geometry[]{
                geometryFactory.createPoint(new Coordinate(13.318, 52.452)),
                geometryFactory.createLineString(new Coordinate[]{
                        new Coordinate(13.3189, 52.4540),
                        new Coordinate(13.3156, 52.4505),
                }),
        });
        RoadClosure closure = roadClosure(collection);

        when(roadClosureRepository.findOverlapping(isNull(), isNull())).thenReturn(List.of(closure));

        mockMvc.perform(get("/api/road-closures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].lon").value(13.318))
                .andExpect(jsonPath("$[0].lat").value(52.452))
                .andExpect(jsonPath("$[0].lines.length()").value(1))
                .andExpect(jsonPath("$[0].lines[0][0][0]").value(13.3189))
                .andExpect(jsonPath("$[0].lines[0][1][1]").value(52.4505))
                .andExpect(jsonPath("$[0].factorType").value("CONSTRUCTION"))
                .andExpect(jsonPath("$[0].severity").value("NO_CLOSURE"))
                .andExpect(jsonPath("$[0].street").value("Wolfensteindamm"))
                .andExpect(jsonPath("$[0].validFrom").value(1753246800000L))
                .andExpect(jsonPath("$[0].validTo").value(1787324400000L));
    }

    @Test
    void roadClosuresEndpointFallsBackToFirstCoordinateWithoutLabelPoint() throws Exception {
        Geometry lineOnly = geometryFactory.createGeometryCollection(new Geometry[]{
                geometryFactory.createLineString(new Coordinate[]{
                        new Coordinate(13.4, 52.5),
                        new Coordinate(13.41, 52.51),
                }),
        });

        when(roadClosureRepository.findOverlapping(isNull(), isNull()))
                .thenReturn(List.of(roadClosure(lineOnly)));

        mockMvc.perform(get("/api/road-closures"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lon").value(13.4))
                .andExpect(jsonPath("$[0].lat").value(52.5));
    }

    @Test
    void roadClosuresEndpointPassesTimeBoundsToRepository() throws Exception {
        when(roadClosureRepository.findOverlapping(eq(1704067200000L), eq(1735689599999L)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/road-closures")
                        .param("from", "1704067200000")
                        .param("to", "1735689599999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    private RoadClosure roadClosure(Geometry geometry) {
        RoadClosure closure = new RoadClosure();
        closure.setId(UUID.randomUUID());
        closure.setFeedId("8/2025");
        closure.setFactorType(ExternalFactorType.CONSTRUCTION);
        closure.setSeverity(RoadClosureSeverity.NO_CLOSURE);
        closure.setStreet("Wolfensteindamm");
        closure.setValidFrom(1753246800000L);
        closure.setValidTo(1787324400000L);
        closure.setGeometry(geometry);
        return closure;
    }
}

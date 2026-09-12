package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.VIZ.RoadClosures;

import berlin.tu.cyclinginfrastructurebackend.domain.RoadClosure;
import berlin.tu.cyclinginfrastructurebackend.domain.SegmentExternalFactor;
import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ExternalFactorType;
import berlin.tu.cyclinginfrastructurebackend.repository.RoadClosureRepository;
import berlin.tu.cyclinginfrastructurebackend.repository.SegmentExternalFactorRepository;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoadClosureDataProviderTest {

    private final GeometryFactory geometryFactory = new GeometryFactory();

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void matchesPointWithinProximityEvenWhenEnvelopesDoNotIntersect() {
        SegmentExternalFactorRepository factorRepository = mock(SegmentExternalFactorRepository.class);
        RoadClosureImportService importService = mock(RoadClosureImportService.class);
        RoadClosureRepository closureRepository = mock(RoadClosureRepository.class);

        RoadClosure closure = new RoadClosure();
        closure.setFeedId("historical:test:1000");
        closure.setFactorType(ExternalFactorType.ROAD_CLOSURE);
        closure.setStreet("Teststraße");
        closure.setValidFrom(1_000L);
        closure.setValidTo(3_000L);
        closure.setGeometry(geometryFactory.createPoint(new Coordinate(13.005, 52.0001)));

        when(importService.isEnabled()).thenReturn(true);
        when(importService.ensureImported()).thenReturn(true);
        when(closureRepository.findAll()).thenReturn(List.of(closure));
        when(factorRepository.existsBySegmentIdAndFactorTypeAndSourceAndValidFrom(
                any(), any(), any(), any()))
                .thenReturn(false);

        RoadClosureDataProvider provider = new RoadClosureDataProvider(
                factorRepository,
                importService,
                closureRepository
        );
        provider.buildIndex();

        StreetSegment segment = new StreetSegment();
        segment.setId(42L);
        segment.setStreetName("Teststraße");
        segment.setGeometry(geometryFactory.createLineString(new Coordinate[]{
                new Coordinate(13.0, 52.0),
                new Coordinate(13.01, 52.0),
        }));

        provider.enrichSegment(segment, 1_500L, 2_000L);

        ArgumentCaptor<Iterable> factorsCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(factorRepository).saveAll(factorsCaptor.capture());
        List<SegmentExternalFactor> factors = new java.util.ArrayList<>();
        factorsCaptor.getValue().forEach(value -> factors.add((SegmentExternalFactor) value));
        assertThat(factors).hasSize(1);
        assertThat(factors.getFirst().getMetadata()).containsEntry("id", "historical:test:1000");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void mergesCandidatesThatShareThePersistedFactorKey() {
        SegmentExternalFactorRepository factorRepository = mock(SegmentExternalFactorRepository.class);
        RoadClosureImportService importService = mock(RoadClosureImportService.class);
        RoadClosureRepository closureRepository = mock(RoadClosureRepository.class);

        RoadClosure first = closure(
                "historical:first:1000",
                1_000L,
                2_500L,
                geometryFactory.createPoint(new Coordinate(13.005, 52.0001))
        );
        RoadClosure second = closure(
                "historical:second:1000",
                1_000L,
                3_500L,
                geometryFactory.createPoint(new Coordinate(13.006, 52.0001))
        );

        when(importService.isEnabled()).thenReturn(true);
        when(importService.ensureImported()).thenReturn(true);
        when(closureRepository.findAll()).thenReturn(List.of(first, second));
        when(factorRepository.existsBySegmentIdAndFactorTypeAndSourceAndValidFrom(
                any(), any(), any(), any()))
                .thenReturn(false);

        RoadClosureDataProvider provider = new RoadClosureDataProvider(
                factorRepository,
                importService,
                closureRepository
        );
        provider.buildIndex();

        StreetSegment segment = new StreetSegment();
        segment.setId(42L);
        segment.setStreetName("Teststraße");
        segment.setGeometry(geometryFactory.createLineString(new Coordinate[]{
                new Coordinate(13.0, 52.0),
                new Coordinate(13.01, 52.0),
        }));

        provider.enrichSegment(segment, 1_500L, 2_000L);

        ArgumentCaptor<Iterable> factorsCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(factorRepository).saveAll(factorsCaptor.capture());
        List<SegmentExternalFactor> factors = new java.util.ArrayList<>();
        factorsCaptor.getValue().forEach(value -> factors.add((SegmentExternalFactor) value));
        assertThat(factors).singleElement().satisfies(factor -> {
            assertThat(factor.getValidFrom()).isEqualTo(1_000L);
            assertThat(factor.getValidTo()).isEqualTo(3_500L);
        });
    }

    private RoadClosure closure(String feedId, Long validFrom, Long validTo,
                                org.locationtech.jts.geom.Geometry geometry) {
        RoadClosure closure = new RoadClosure();
        closure.setFeedId(feedId);
        closure.setFactorType(ExternalFactorType.CONSTRUCTION);
        closure.setStreet("Teststraße");
        closure.setValidFrom(validFrom);
        closure.setValidTo(validTo);
        closure.setGeometry(geometry);
        return closure;
    }
}

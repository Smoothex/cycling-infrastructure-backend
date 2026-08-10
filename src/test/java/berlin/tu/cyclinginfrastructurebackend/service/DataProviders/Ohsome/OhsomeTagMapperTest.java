package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayLocation;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OhsomeTagMapperTest {

    private final OhsomeTagMapper mapper = new OhsomeTagMapper();

    @Test
    void mapsExistingInfrastructureContractAndSideSpecificPrecedence() {
        OhsomeInfrastructureAttributes attributes = mapper.map(Map.ofEntries(
                Map.entry("surface", "asphalt"),
                Map.entry("smoothness", "good"),
                Map.entry("lit", "yes"),
                Map.entry("highway", "residential"),
                Map.entry("cycleway", "lane"),
                Map.entry("cycleway:right", "track"),
                Map.entry("cycleway:right:surface", "concrete"),
                Map.entry("cycleway:right:width", "2.5"),
                Map.entry("oneway:bicycle", "yes")
        ));

        assertThat(attributes.surface()).isEqualTo("asphalt");
        assertThat(attributes.smoothness()).isEqualTo("good");
        assertThat(attributes.lit()).isEqualTo("yes");
        assertThat(attributes.highway()).isEqualTo("residential");
        assertThat(attributes.cyclewayType()).isEqualTo(CyclewayType.TRACK);
        assertThat(attributes.cyclewayLocation()).isEqualTo(CyclewayLocation.RIGHT);
        assertThat(attributes.cyclewaySurface()).isEqualTo("concrete");
        assertThat(attributes.cyclewayWidth()).isEqualTo(2.5);
        assertThat(attributes.bicycleOneway()).isTrue();
    }

    @Test
    void normalizesDeprecatedOppositeTrackWithoutMutatingInput() {
        Map<String, String> tags = Map.of("cycleway", "opposite_track", "surface", "paving_stones");

        OhsomeInfrastructureAttributes attributes = mapper.map(tags);

        assertThat(attributes.cyclewayType()).isEqualTo(CyclewayType.TRACK);
        assertThat(attributes.cyclewayLocation()).isEqualTo(CyclewayLocation.UNKNOWN);
        assertThat(attributes.cyclewaySurface()).isEqualTo("paving_stones");
        assertThat(attributes.bicycleOneway()).isFalse();
        assertThat(tags).containsEntry("cycleway", "opposite_track");
    }

    @Test
    void immutableAttributesCanPopulateExistingSegmentEventFields() {
        SegmentEvent event = new SegmentEvent();

        mapper.map(Map.of("highway", "cycleway", "cycleway", "no", "oneway:bicycle", "-1"))
                .applyTo(event);

        assertThat(event.getHighway()).isEqualTo("cycleway");
        assertThat(event.getCyclewayType()).isEqualTo(CyclewayType.NO);
        assertThat(event.getCyclewayLocation()).isEqualTo(CyclewayLocation.UNKNOWN);
        assertThat(event.getCyclewaySurface()).isNull();
        assertThat(event.getCyclewayWidth()).isNull();
        assertThat(event.getBicycleOneway()).isFalse();
    }
}

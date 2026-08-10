package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import berlin.tu.cyclinginfrastructurebackend.domain.SegmentEvent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayLocation;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayType;

/** Immutable result of mapping the OSM tags used by the public enrichment contract. */
public record OhsomeInfrastructureAttributes(
        String surface,
        String smoothness,
        String lit,
        String highway,
        CyclewayType cyclewayType,
        CyclewayLocation cyclewayLocation,
        String cyclewaySurface,
        Double cyclewayWidth,
        Boolean bicycleOneway
) {

    public void applyTo(SegmentEvent event) {
        event.setSurface(surface);
        event.setSmoothness(smoothness);
        event.setLit(lit);
        event.setHighway(highway);
        event.setCyclewayType(cyclewayType);
        event.setCyclewayLocation(cyclewayLocation);
        event.setCyclewaySurface(cyclewaySurface);
        event.setCyclewayWidth(cyclewayWidth);
        event.setBicycleOneway(bicycleOneway);
    }
}

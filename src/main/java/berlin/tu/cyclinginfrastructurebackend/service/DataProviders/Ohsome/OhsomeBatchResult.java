package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayLocation;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.EnrichmentStatus;

/** Prepared result applied to every event for one segment and UTC month. */
public record OhsomeBatchResult(
        long segmentId,
        long monthStartMillis,
        long monthEndMillis,
        boolean enriched,
        EnrichmentStatus status,
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

    public static OhsomeBatchResult matched(OhsomeWorkItem item, OhsomeInfrastructureAttributes attributes) {
        return new OhsomeBatchResult(
                item.segmentId(), item.monthStartMillis(), item.monthEndMillis(), true, EnrichmentStatus.DONE,
                attributes.surface(), attributes.smoothness(), attributes.lit(), attributes.highway(),
                attributes.cyclewayType(), attributes.cyclewayLocation(), attributes.cyclewaySurface(),
                attributes.cyclewayWidth(), attributes.bicycleOneway()
        );
    }

    public static OhsomeBatchResult noData(OhsomeWorkItem item) {
        return empty(item, EnrichmentStatus.DONE);
    }

    public static OhsomeBatchResult error(OhsomeWorkItem item) {
        return empty(item, EnrichmentStatus.ERROR);
    }

    private static OhsomeBatchResult empty(OhsomeWorkItem item, EnrichmentStatus status) {
        return new OhsomeBatchResult(
                item.segmentId(), item.monthStartMillis(), item.monthEndMillis(), false, status,
                null, null, null, null, null, null, null, null, null
        );
    }
}

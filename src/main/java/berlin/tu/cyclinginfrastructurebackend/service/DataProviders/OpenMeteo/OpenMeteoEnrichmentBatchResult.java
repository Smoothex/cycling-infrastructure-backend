package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.OpenMeteo;

/** Outcome of one scheduled bulk Open-Meteo claim. */
public record OpenMeteoEnrichmentBatchResult(
        int mappedSegments,
        int invalidEvents,
        int claimedLocations,
        int claimedEvents,
        int cachedEvents,
        int downloadedRows,
        int downloadedEvents,
        int missingEvents
) {

    public static OpenMeteoEnrichmentBatchResult noWork() {
        return new OpenMeteoEnrichmentBatchResult(0, 0, 0, 0, 0, 0, 0, 0);
    }

    public int enrichedEvents() {
        return cachedEvents + downloadedEvents;
    }
}

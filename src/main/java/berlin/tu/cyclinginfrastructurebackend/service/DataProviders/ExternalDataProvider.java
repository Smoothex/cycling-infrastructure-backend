package berlin.tu.cyclinginfrastructurebackend.service.DataProviders;

import berlin.tu.cyclinginfrastructurebackend.domain.StreetSegment;


public interface ExternalDataProvider {

    /**
     * Fetches external data relevant to the given segment and time window,
     * and persists it as {@link berlin.tu.cyclinginfrastructurebackend.domain.SegmentExternalFactor} records.
     *
     * @param segment           the street segment to enrich
     * @param fromEpochMillis   start of the time window (inclusive), epoch milliseconds
     * @param toEpochMillis     end of the time window (inclusive), epoch milliseconds
     */
    void enrichSegment(StreetSegment segment, Long fromEpochMillis, Long toEpochMillis);
}

package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.VIZ.Traffic;

import java.util.Optional;

record TrafficLookupResult(boolean sourceFileAvailable, Optional<TrafficMeasurement> measurement) {

    static TrafficLookupResult sourceMissing() {
        return new TrafficLookupResult(false, Optional.empty());
    }

    static TrafficLookupResult noMeasurement() {
        return new TrafficLookupResult(true, Optional.empty());
    }

    static TrafficLookupResult found(TrafficMeasurement measurement) {
        return new TrafficLookupResult(true, Optional.of(measurement));
    }
}

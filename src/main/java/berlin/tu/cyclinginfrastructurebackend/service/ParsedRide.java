package berlin.tu.cyclinginfrastructurebackend.service;

import berlin.tu.cyclinginfrastructurebackend.domain.Ride;

import java.util.List;

/** The persistent ride aggregate together with its non-persistent source trace. */
public record ParsedRide(Ride ride, List<RideTracePoint> trace) {

    public ParsedRide {
        trace = List.copyOf(trace);
    }
}

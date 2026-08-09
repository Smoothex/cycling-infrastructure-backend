package berlin.tu.cyclinginfrastructurebackend.service;

import org.locationtech.jts.geom.Point;

/** A transient GPS sample used only while importing one ride. */
public record RideTracePoint(Point location, Long timestamp) {
}

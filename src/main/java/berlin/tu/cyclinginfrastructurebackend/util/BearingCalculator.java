package berlin.tu.cyclinginfrastructurebackend.util;

import com.graphhopper.util.PointList;

/**
 * Utility class for computing compass bearings from geographic coordinates.
 * <p>
 * Provides methods to calculate the travel direction along a path segment,
 * using length-weighted averaging for curved edges to produce stable bearings.
 * All bearings are returned in degrees in the range [0, 360).
 */
public final class BearingCalculator {

    private BearingCalculator() {
        // Utility class - prevent instantiation
    }

    /**
     * Computes a travel direction for a path section. A single edge in the route can contain
     * several intermediate geometry points, so it is not always a perfectly straight line.
     * Instead of using only the first and last point, this method calculates the bearing at
     * every small segment between the two points. Then it averages these bearings weighted
     * by the length of each segment (longer sub-segments contribute more), resulting in a
     * more stable overall direction for curved edges.
     *
     * @param points    route geometry as lat/lon points
     * @param fromIndex the index of the first point in the point list
     * @param toIndex   the index of the last point in the point list
     * @return the combined bearing in degrees in the range {@code [0, 360)},
     *         or {@code null} if the indexes do not define a valid path section
     */
    public static Double calculateBearing(PointList points, int fromIndex, int toIndex) {
        if (points == null || fromIndex < 0 || toIndex >= points.size() || toIndex <= fromIndex) {
            return null;
        }

        double weightedSin = 0.0;
        double weightedCos = 0.0;

        for (int i = fromIndex; i < toIndex; i++) {
            double lat1 = points.getLat(i);
            double lon1 = points.getLon(i);
            double lat2 = points.getLat(i + 1);
            double lon2 = points.getLon(i + 1);

            double segmentLength = approximateDistanceMeters(lat1, lon1, lat2, lon2);
            if (segmentLength == 0.0) {
                continue;
            }

            double bearingRadians = Math.toRadians(initialBearingDegrees(lat1, lon1, lat2, lon2));
            weightedSin += Math.sin(bearingRadians) * segmentLength;
            weightedCos += Math.cos(bearingRadians) * segmentLength;
        }

        // Fallback to simple start-to-end bearing if all segments had zero length
        if (weightedSin == 0.0 && weightedCos == 0.0) {
            return normalizeDegrees(initialBearingDegrees(
                    points.getLat(fromIndex),
                    points.getLon(fromIndex),
                    points.getLat(toIndex),
                    points.getLon(toIndex)
            ));
        }

        return normalizeDegrees(Math.toDegrees(Math.atan2(weightedSin, weightedCos)));
    }

    /**
     * Calculates the initial compass bearing from one coordinate to the next
     * using the great-circle bearing formula.
     *
     * @param lat1 latitude of the start point
     * @param lon1 longitude of the start point
     * @param lat2 latitude of the end point
     * @param lon2 longitude of the end point
     * @return the bearing in degrees (not yet normalized to 0..360 range)
     * @see <a href="https://www.movable-type.co.uk/scripts/latlong.html#bearing">Great-Circle Bearing</a>
     */
    public static double initialBearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaLambda = Math.toRadians(lon2 - lon1);

        double y = Math.sin(deltaLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) -
                   Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLambda);

        return Math.toDegrees(Math.atan2(y, x));
    }

    /**
     * Normalizes a bearing into the common compass range from {@code 0} to {@code 360} degrees.
     *
     * @param degrees the raw bearing value
     * @return the same direction expressed in the {@code [0, 360)} range
     */
    public static double normalizeDegrees(double degrees) {
        return (degrees % 360.0 + 360.0) % 360.0;
    }

    /**
     * Estimates the distance in meters between two nearby coordinates using
     * an approximation. This is fast and accurate enough for
     * short distances (within a city), which is sufficient for weighting
     * bearing calculations.
     *
     * @param lat1 latitude of the start point
     * @param lon1 longitude of the start point
     * @param lat2 latitude of the end point
     * @param lon2 longitude of the end point
     * @return the approximate distance in meters
     */
    public static double approximateDistanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double latMeters = (lat2 - lat1) * 111_320.0;
        double avgLatRadians = Math.toRadians((lat1 + lat2) / 2.0);
        double lonMeters = (lon2 - lon1) * 111_320.0 * Math.cos(avgLatRadians);
        return Math.hypot(latMeters, lonMeters);
    }
}

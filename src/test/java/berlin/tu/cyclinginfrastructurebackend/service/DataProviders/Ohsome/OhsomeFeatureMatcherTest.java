package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OhsomeFeatureMatcherTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();
    private static final double METERS_PER_LATITUDE_DEGREE = 111_320.0;

    @Test
    void exactNormalizedNameWinsOverCloserUnnamedCandidate() {
        LineString segment = eastWestLine(52.5, 13.4, 13.401);
        OhsomeFeature exactName = feature(20, offsetNorth(segment, 5), "  TEST\u3000Straße ");
        OhsomeFeature unnamed = feature(10, offsetNorth(segment, 1), null);

        OhsomeFeatureMatch match = new OhsomeFeatureMatcher(List.of(unnamed, exactName))
                .match(segment, "Test Straße");

        assertThat(match.outcome()).isEqualTo(OhsomeFeatureMatch.Outcome.MATCHED);
        assertThat(match.feature()).get().extracting(OhsomeFeature::osmId).isEqualTo(20L);
    }

    @Test
    void conflictingNonBlankNameIsRejected() {
        LineString segment = eastWestLine(52.5, 13.4, 13.401);
        OhsomeFeature feature = feature(1, segment, "Different Street");

        OhsomeFeatureMatch match = new OhsomeFeatureMatcher(List.of(feature))
                .match(segment, "Expected Street");

        assertThat(match.outcome()).isEqualTo(OhsomeFeatureMatch.Outcome.NO_MATCH);
    }

    @Test
    void twoGeometricallyEquivalentCandidatesAreReportedAsAmbiguous() {
        LineString segment = eastWestLine(52.5, 13.4, 13.401);
        OhsomeFeature north = feature(20, offsetNorth(segment, 2), null);
        OhsomeFeature south = feature(10, offsetNorth(segment, -2), null);

        OhsomeFeatureMatch match = new OhsomeFeatureMatcher(List.of(north, south))
                .match(segment, null);

        assertThat(match.outcome()).isEqualTo(OhsomeFeatureMatch.Outcome.AMBIGUOUS);
        assertThat(match.feature()).isEmpty();
    }

    @Test
    void rankingDoesNotDependOnInputOrder() {
        LineString segment = eastWestLine(52.5, 13.4, 13.401);
        OhsomeFeature near = feature(99, offsetNorth(segment, 1), null);
        OhsomeFeature farther = feature(1, offsetNorth(segment, 10), null);

        OhsomeFeatureMatch first = new OhsomeFeatureMatcher(List.of(near, farther)).match(segment, null);
        OhsomeFeatureMatch second = new OhsomeFeatureMatcher(List.of(farther, near)).match(segment, null);

        assertThat(first.outcome()).isEqualTo(OhsomeFeatureMatch.Outcome.MATCHED);
        assertThat(second.outcome()).isEqualTo(OhsomeFeatureMatch.Outcome.MATCHED);
        assertThat(first.matchedFeature().osmId()).isEqualTo(99L);
        assertThat(second.matchedFeature().osmId()).isEqualTo(99L);
    }

    @Test
    void insufficientFullLineCoverageIsRejected() {
        LineString segment = eastWestLine(52.5, 13.4, 13.402);
        LineString partial = eastWestLine(52.5, 13.4, 13.4005);

        OhsomeFeatureMatch match = new OhsomeFeatureMatcher(List.of(feature(1, partial, null)))
                .match(segment, null);

        assertThat(match.outcome()).isEqualTo(OhsomeFeatureMatch.Outcome.NO_MATCH);
    }

    @Test
    void reversedWayDirectionStillMatches() {
        LineString segment = eastWestLine(52.5, 13.4, 13.401);
        Coordinate[] reversed = segment.getCoordinates();
        LineString reverse = line(reversed[1], reversed[0]);

        OhsomeFeatureMatch match = new OhsomeFeatureMatcher(List.of(feature(1, reverse, null)))
                .match(segment, null);

        assertThat(match.outcome()).isEqualTo(OhsomeFeatureMatch.Outcome.MATCHED);
    }

    @Test
    void perpendicularWayCrossingAtTheCentroidIsRejected() {
        LineString segment = eastWestLine(52.5, 13.4, 13.401);
        LineString crossing = line(
                new Coordinate(13.4005, 52.4995),
                new Coordinate(13.4005, 52.5005));

        OhsomeFeatureMatch match = new OhsomeFeatureMatcher(List.of(feature(1, crossing, null)))
                .match(segment, null);

        assertThat(match.outcome()).isEqualTo(OhsomeFeatureMatch.Outcome.NO_MATCH);
    }

    @Test
    void candidateOutsideTheFifteenMeterCorridorIsRejected() {
        LineString segment = eastWestLine(52.5, 13.4, 13.401);

        OhsomeFeatureMatch match = new OhsomeFeatureMatcher(
                List.of(feature(1, offsetNorth(segment, 16), null)))
                .match(segment, null);

        assertThat(match.outcome()).isEqualTo(OhsomeFeatureMatch.Outcome.NO_MATCH);
    }

    @Test
    void shortSegmentsUseConservativeDistanceAndAmbiguityRule() {
        double oneMeterLongitude = 1.0 / (METERS_PER_LATITUDE_DEGREE * Math.cos(Math.toRadians(52.5)));
        LineString segment = line(
                new Coordinate(13.4, 52.5),
                new Coordinate(13.4 + oneMeterLongitude, 52.5));
        OhsomeFeature north = feature(1, offsetNorth(segment, 1), null);
        OhsomeFeature south = feature(2, offsetNorth(segment, -1), null);

        OhsomeFeatureMatch match = new OhsomeFeatureMatcher(List.of(north, south))
                .match(segment, null);

        assertThat(match.outcome()).isEqualTo(OhsomeFeatureMatch.Outcome.AMBIGUOUS);
    }

    @Test
    void zeroLengthSegmentIsNeverAssignedToARoad() {
        Coordinate point = new Coordinate(13.4, 52.5);
        LineString segment = line(point, new Coordinate(point));
        LineString road = eastWestLine(52.5, 13.399, 13.401);

        OhsomeFeatureMatch match = new OhsomeFeatureMatcher(List.of(feature(1, road, null)))
                .match(segment, null);

        assertThat(match.outcome()).isEqualTo(OhsomeFeatureMatch.Outcome.NO_MATCH);
    }

    @Test
    void nameNormalizationUsesNfkcWhitespaceAndRootCaseFolding() {
        assertThat(OhsomeFeatureMatcher.normalizeName("  ＴＥＳＴ\u3000Straße\t"))
                .isEqualTo("test straße");
    }

    private OhsomeFeature feature(long id, LineString geometry, String name) {
        return new OhsomeFeature(id, geometry, name == null ? Map.of() : Map.of("name", name));
    }

    private LineString eastWestLine(double latitude, double startLongitude, double endLongitude) {
        return line(new Coordinate(startLongitude, latitude), new Coordinate(endLongitude, latitude));
    }

    private LineString offsetNorth(LineString line, double meters) {
        double latitudeOffset = meters / METERS_PER_LATITUDE_DEGREE;
        Coordinate[] sourceCoordinates = line.getCoordinates();
        Coordinate[] shiftedCoordinates = new Coordinate[sourceCoordinates.length];
        for (int index = 0; index < sourceCoordinates.length; index++) {
            shiftedCoordinates[index] = new Coordinate(
                    sourceCoordinates[index].x,
                    sourceCoordinates[index].y + latitudeOffset);
        }
        return GEOMETRY_FACTORY.createLineString(shiftedCoordinates);
    }

    private LineString line(Coordinate... coordinates) {
        LineString line = GEOMETRY_FACTORY.createLineString(coordinates);
        line.setSRID(4326);
        return line;
    }
}

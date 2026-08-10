package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.Ohsome;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * Conservative full-line matcher for GraphHopper segments and historical OSM ways.
 * Candidate ranking is stable regardless of Parquet row or spatial-index iteration order.
 */
public final class OhsomeFeatureMatcher {

    private static final double GEOMETRY_EPSILON = 1.0e-6;

    private final Config config;
    private final OhsomeCoordinateTransformer coordinateTransformer;
    private final STRtree index;

    public OhsomeFeatureMatcher(Collection<OhsomeFeature> features) {
        this(features, Config.defaults());
    }

    public OhsomeFeatureMatcher(Collection<OhsomeFeature> features, Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.coordinateTransformer = new OhsomeCoordinateTransformer();
        this.index = new STRtree();

        features.stream()
                .sorted(Comparator.comparingLong(OhsomeFeature::osmId))
                .map(this::project)
                .forEach(feature -> index.insert(feature.geometry().getEnvelopeInternal(), feature));
        index.build();
    }

    public OhsomeFeatureMatch match(LineString segment, String streetName) {
        if (segment == null || segment.isEmpty() || segment.getNumPoints() < 2) {
            return OhsomeFeatureMatch.noMatch();
        }

        LineString projectedSegment;
        try {
            projectedSegment = coordinateTransformer.toMeters(segment);
        } catch (IllegalArgumentException exception) {
            return OhsomeFeatureMatch.noMatch();
        }
        if (projectedSegment.getLength() <= GEOMETRY_EPSILON) {
            return OhsomeFeatureMatch.noMatch();
        }

        LengthIndexedLine indexedSegment = new LengthIndexedLine(projectedSegment);
        List<Sample> samples = samples(projectedSegment, indexedSegment);
        boolean hasBearing = samples.stream().anyMatch(sample -> sample.bearing().isPresent());
        if (projectedSegment.getLength() < config.shortSegmentThresholdMeters() || !hasBearing) {
            return matchShort(projectedSegment, streetName);
        }
        return matchNormal(projectedSegment, samples, streetName);
    }

    private OhsomeFeatureMatch matchNormal(LineString segment,
                                           List<Sample> samples,
                                           String streetName) {
        List<CandidateScore> scores = candidates(segment, config.searchRadiusMeters()).stream()
                .filter(candidate -> !namesConflict(streetName, candidate.normalizedName()))
                .map(candidate -> scoreNormal(candidate, samples, streetName))
                .filter(Objects::nonNull)
                .sorted(normalComparator())
                .toList();

        if (scores.isEmpty()) {
            return OhsomeFeatureMatch.noMatch();
        }
        if (scores.size() > 1 && normalNearTie(scores.get(0), scores.get(1))) {
            return OhsomeFeatureMatch.ambiguous();
        }
        return OhsomeFeatureMatch.matched(scores.getFirst().feature().source());
    }

    private CandidateScore scoreNormal(IndexedFeature candidate,
                                       List<Sample> samples,
                                       String streetName) {
        LengthIndexedLine candidateLine = new LengthIndexedLine(candidate.geometry());
        List<Double> distances = new ArrayList<>(samples.size());
        List<Double> bearingDifferences = new ArrayList<>(samples.size());
        int covered = 0;

        for (Sample sample : samples) {
            double candidateIndex = candidateLine.project(sample.coordinate());
            Coordinate closest = candidateLine.extractPoint(candidateIndex);
            double distance = sample.coordinate().distance(closest);
            distances.add(distance);
            if (distance <= config.searchRadiusMeters()) {
                covered++;
                OptionalDouble candidateBearing = bearingAt(candidateLine, candidate.geometry().getLength(), candidateIndex);
                if (sample.bearing().isPresent() && candidateBearing.isPresent()) {
                    bearingDifferences.add(undirectedDifference(
                            sample.bearing().getAsDouble(), candidateBearing.getAsDouble()));
                }
            }
        }

        double coverage = covered / (double) samples.size();
        if (coverage < config.minimumCoverage()) {
            return null;
        }
        if (bearingDifferences.isEmpty()) {
            return null;
        }

        double bearingDifference = median(bearingDifferences);
        if (bearingDifference > config.maximumBearingDifferenceDegrees()) {
            return null;
        }

        return new CandidateScore(
                candidate,
                nameClass(streetName, candidate.normalizedName()),
                coverage,
                median(distances),
                bearingDifference
        );
    }

    private OhsomeFeatureMatch matchShort(LineString segment, String streetName) {
        List<ShortCandidateScore> scores = candidates(segment, config.shortSegmentRadiusMeters()).stream()
                .filter(candidate -> !namesConflict(streetName, candidate.normalizedName()))
                .map(candidate -> new ShortCandidateScore(
                        candidate,
                        nameClass(streetName, candidate.normalizedName()),
                        segment.distance(candidate.geometry())))
                .filter(score -> score.distance() <= config.shortSegmentRadiusMeters())
                .sorted(shortComparator())
                .toList();

        if (scores.isEmpty()) {
            return OhsomeFeatureMatch.noMatch();
        }
        if (scores.size() > 1 && shortNearTie(scores.get(0), scores.get(1))) {
            return OhsomeFeatureMatch.ambiguous();
        }
        return OhsomeFeatureMatch.matched(scores.getFirst().feature().source());
    }

    @SuppressWarnings("unchecked")
    private List<IndexedFeature> candidates(LineString segment, double radiusMeters) {
        Envelope envelope = new Envelope(segment.getEnvelopeInternal());
        envelope.expandBy(radiusMeters);
        return (List<IndexedFeature>) (List<?>) index.query(envelope);
    }

    private List<Sample> samples(LineString segment, LengthIndexedLine indexedLine) {
        TreeMap<Double, Coordinate> coordinatesByIndex = new TreeMap<>();
        double length = segment.getLength();
        for (double index = 0.0; index < length; index += config.sampleSpacingMeters()) {
            coordinatesByIndex.put(index, indexedLine.extractPoint(index));
        }
        coordinatesByIndex.put(length, indexedLine.extractPoint(length));

        double vertexIndex = 0.0;
        Coordinate[] vertices = segment.getCoordinates();
        coordinatesByIndex.put(0.0, vertices[0]);
        for (int index = 1; index < vertices.length; index++) {
            vertexIndex += vertices[index - 1].distance(vertices[index]);
            coordinatesByIndex.put(vertexIndex, vertices[index]);
        }

        return coordinatesByIndex.entrySet().stream()
                .map(entry -> new Sample(
                        new Coordinate(entry.getValue()),
                        bearingAt(indexedLine, length, entry.getKey())))
                .toList();
    }

    private OptionalDouble bearingAt(LengthIndexedLine line, double length, double index) {
        if (length <= GEOMETRY_EPSILON) {
            return OptionalDouble.empty();
        }

        double[] probes = {1.0, 5.0, length};
        for (double probe : probes) {
            double lower = Math.max(0.0, index - probe);
            double upper = Math.min(length, index + probe);
            Coordinate before = line.extractPoint(lower);
            Coordinate after = line.extractPoint(upper);
            if (before.distance(after) > GEOMETRY_EPSILON) {
                double angle = Math.toDegrees(Math.atan2(after.y - before.y, after.x - before.x));
                return OptionalDouble.of(normalizeUndirected(angle));
            }
        }
        return OptionalDouble.empty();
    }

    private double normalizeUndirected(double angle) {
        double normalized = angle % 180.0;
        return normalized < 0.0 ? normalized + 180.0 : normalized;
    }

    private double undirectedDifference(double first, double second) {
        double difference = Math.abs(first - second);
        return Math.min(difference, 180.0 - difference);
    }

    private int nameClass(String segmentName, String normalizedCandidateName) {
        String normalizedSegmentName = normalizeName(segmentName);
        if (!normalizedSegmentName.isEmpty() && normalizedSegmentName.equals(normalizedCandidateName)) {
            return 1;
        }
        return 0;
    }

    private boolean namesConflict(String segmentName, String normalizedCandidateName) {
        String normalizedSegmentName = normalizeName(segmentName);
        return !normalizedSegmentName.isEmpty()
                && !normalizedCandidateName.isEmpty()
                && !normalizedSegmentName.equals(normalizedCandidateName);
    }

    static String normalizeName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private Comparator<CandidateScore> normalComparator() {
        return Comparator.comparingDouble(CandidateScore::coverage).reversed()
                .thenComparing(Comparator.comparingInt(CandidateScore::nameClass).reversed())
                .thenComparingDouble(CandidateScore::medianDistance)
                .thenComparingDouble(CandidateScore::bearingDifference)
                .thenComparingLong(score -> score.feature().source().osmId());
    }

    private Comparator<ShortCandidateScore> shortComparator() {
        return Comparator.comparingInt(ShortCandidateScore::nameClass).reversed()
                .thenComparingDouble(ShortCandidateScore::distance)
                .thenComparingLong(score -> score.feature().source().osmId());
    }

    private boolean normalNearTie(CandidateScore first, CandidateScore second) {
        return first.nameClass() == second.nameClass()
                && Math.abs(first.coverage() - second.coverage()) < config.ambiguousCoverageDifference()
                && Math.abs(first.medianDistance() - second.medianDistance()) < config.ambiguousDistanceDifferenceMeters()
                && Math.abs(first.bearingDifference() - second.bearingDifference())
                < config.ambiguousBearingDifferenceDegrees();
    }

    private boolean shortNearTie(ShortCandidateScore first, ShortCandidateScore second) {
        return first.nameClass() == second.nameClass()
                && Math.abs(first.distance() - second.distance()) < config.ambiguousDistanceDifferenceMeters();
    }

    private double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int middle = sorted.length / 2;
        if (sorted.length % 2 == 1) {
            return sorted[middle];
        }
        return (sorted[middle - 1] + sorted[middle]) / 2.0;
    }

    private IndexedFeature project(OhsomeFeature feature) {
        return new IndexedFeature(
                feature,
                coordinateTransformer.toMeters(feature.geometry()),
                normalizeName(feature.name())
        );
    }

    public record Config(
            double sampleSpacingMeters,
            double searchRadiusMeters,
            double minimumCoverage,
            double maximumBearingDifferenceDegrees,
            double shortSegmentThresholdMeters,
            double shortSegmentRadiusMeters,
            double ambiguousCoverageDifference,
            double ambiguousDistanceDifferenceMeters,
            double ambiguousBearingDifferenceDegrees
    ) {
        public Config {
            requirePositive(sampleSpacingMeters, "sampleSpacingMeters");
            requirePositive(searchRadiusMeters, "searchRadiusMeters");
            if (minimumCoverage <= 0.0 || minimumCoverage > 1.0) {
                throw new IllegalArgumentException("minimumCoverage must be in (0, 1]");
            }
            if (maximumBearingDifferenceDegrees <= 0.0 || maximumBearingDifferenceDegrees > 90.0) {
                throw new IllegalArgumentException("maximumBearingDifferenceDegrees must be in (0, 90]");
            }
            requirePositive(shortSegmentThresholdMeters, "shortSegmentThresholdMeters");
            requirePositive(shortSegmentRadiusMeters, "shortSegmentRadiusMeters");
            requirePositive(ambiguousCoverageDifference, "ambiguousCoverageDifference");
            requirePositive(ambiguousDistanceDifferenceMeters, "ambiguousDistanceDifferenceMeters");
            requirePositive(ambiguousBearingDifferenceDegrees, "ambiguousBearingDifferenceDegrees");
        }

        public static Config defaults() {
            return new Config(5.0, 15.0, 0.80, 45.0, 2.0, 5.0, 0.10, 2.0, 10.0);
        }

        private static void requirePositive(double value, String name) {
            if (!Double.isFinite(value) || value <= 0.0) {
                throw new IllegalArgumentException(name + " must be finite and positive");
            }
        }
    }

    private record IndexedFeature(OhsomeFeature source, LineString geometry, String normalizedName) {
    }

    private record Sample(Coordinate coordinate, OptionalDouble bearing) {
    }

    private record CandidateScore(
            IndexedFeature feature,
            int nameClass,
            double coverage,
            double medianDistance,
            double bearingDifference
    ) {
    }

    private record ShortCandidateScore(IndexedFeature feature, int nameClass, double distance) {
    }
}

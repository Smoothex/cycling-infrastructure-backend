package berlin.tu.cyclinginfrastructurebackend.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Segment-event data prepared by detour analysis for the final write transaction. */
public record DetourAnalysisResult(
        Map<Integer, Double> avoidedEdgeBearings,
        Map<Integer, Long> avoidedEdgeTimestamps,
        Map<Integer, Double> chosenEdgeBearings,
        Map<Integer, Long> chosenEdgeTimestamps
) {

    public DetourAnalysisResult {
        avoidedEdgeBearings = immutableCopy(avoidedEdgeBearings);
        avoidedEdgeTimestamps = immutableCopy(avoidedEdgeTimestamps);
        chosenEdgeBearings = immutableCopy(chosenEdgeBearings);
        chosenEdgeTimestamps = immutableCopy(chosenEdgeTimestamps);
    }

    public static DetourAnalysisResult empty() {
        return new DetourAnalysisResult(Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static <K, V> Map<K, V> immutableCopy(Map<K, V> source) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}

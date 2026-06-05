package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

public record SegmentTrafficStatsDto(
        Long segmentId,
        long trafficEnrichedEventCount,
        long trafficMeasuredEventCount,
        Double averageTrafficVolumeKfz,
        Double averageTrafficSpeedKfz,
        Double averageTrafficVolumePkw,
        Double averageTrafficSpeedPkw,
        Double averageTrafficVolumeLkw,
        Double averageTrafficSpeedLkw,
        String dominantTrafficCondition
) {

    public static SegmentTrafficStatsDto from(Object[] row) {
        return new SegmentTrafficStatsDto(
                asLongObject(row[0]),
                asLong(row[1]),
                asLong(row[2]),
                asDouble(row[3]),
                asDouble(row[4]),
                asDouble(row[5]),
                asDouble(row[6]),
                asDouble(row[7]),
                asDouble(row[8]),
                row[9] != null ? row[9].toString() : null
        );
    }

    public Double measuredTrafficShare(long eventObservationCount) {
        return eventObservationCount > 0
                ? (double) trafficMeasuredEventCount / eventObservationCount
                : null;
    }

    private static Long asLongObject(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static Double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}

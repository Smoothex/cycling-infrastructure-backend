package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

public record TimeSeriesBucketDto(
        Long bucketStartEpochMillis,
        String label,
        long totalCount,
        long avoidanceCount,
        long preferenceCount,
        Double avoidanceShare,
        Double preferenceShare
) {
}

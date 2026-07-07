package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

public record DimensionBucketDto(
        String dimension,
        String value,
        long totalCount,
        long avoidanceCount,
        long preferenceCount,
        Double avoidanceShare,
        Double preferenceShare,
        Double averageTemperature2m,
        Double averagePrecipitation,
        Double averageWindSpeed10m,
        Double averageRelativeWindAngleDegrees,
        Double averageGradientPercent,
        Double averageTrafficVolumeKfz,
        Double averageTrafficSpeedKfz
) {
}

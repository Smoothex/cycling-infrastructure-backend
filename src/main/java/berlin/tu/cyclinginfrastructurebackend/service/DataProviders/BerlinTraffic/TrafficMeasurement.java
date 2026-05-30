package berlin.tu.cyclinginfrastructurebackend.service.DataProviders.BerlinTraffic;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficSourceType;

record TrafficMeasurement(
        TrafficSourceType sourceType,
        Integer volumeKfz,
        Double speedKfz,
        Integer volumePkw,
        Double speedPkw,
        Integer volumeLkw,
        Double speedLkw,
        Double quality,
        Double completenessPercent
) {
}

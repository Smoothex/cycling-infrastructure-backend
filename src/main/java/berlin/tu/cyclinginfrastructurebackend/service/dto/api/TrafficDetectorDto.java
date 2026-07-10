package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

import berlin.tu.cyclinginfrastructurebackend.domain.TrafficDetector;

import java.time.LocalDate;
import java.util.List;

public record TrafficDetectorDto(
        String detName,
        String detNameNeu,
        String mqName,
        String street,
        String position,
        String positionDetail,
        String direction,
        String lane,
        LocalDate activeFrom,
        LocalDate activeTo,
        boolean deinstalled,
        double lon,
        double lat
) {

    public static TrafficDetectorDto from(TrafficDetector detector) {
        return new TrafficDetectorDto(
                detector.getDetNameAlt(),
                detector.getDetNameNeu(),
                detector.getMqKurzname(),
                detector.getStreet(),
                detector.getPosition(),
                detector.getPositionDetail(),
                detector.getDirection(),
                detector.getLane(),
                detector.getActiveFrom(),
                detector.getActiveTo(),
                Boolean.TRUE.equals(detector.getDeinstalled()),
                detector.getLocation().getX(),
                detector.getLocation().getY()
        );
    }

    /**
     * @param matchRadiusMeters the enrichment match radius: segments within this
     *                          distance of a detector can be enriched by it
     */
    public record TrafficDetectorsDto(double matchRadiusMeters, List<TrafficDetectorDto> detectors) {
    }
}

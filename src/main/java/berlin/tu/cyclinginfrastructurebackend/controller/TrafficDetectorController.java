package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.repository.TrafficDetectorRepository;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.TrafficDetectorDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.TrafficDetectorDto.TrafficDetectorsDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/traffic")
public class TrafficDetectorController {

    private final TrafficDetectorRepository detectorRepository;
    private final double matchRadiusMeters;

    public TrafficDetectorController(TrafficDetectorRepository detectorRepository,
                                     @Value("${enrichment.traffic.match-radius-meters:75}") double matchRadiusMeters) {
        this.detectorRepository = detectorRepository;
        this.matchRadiusMeters = matchRadiusMeters;
    }

    /**
     * All traffic detectors from the Berlin Stammdaten import with their WGS84
     * positions, plus the enrichment match radius (segments within that distance
     * of a detector can receive its measurements).
     */
    @GetMapping("/detectors")
    public TrafficDetectorsDto getDetectors() {
        List<TrafficDetectorDto> detectors = detectorRepository.findAll().stream()
                .filter(detector -> detector.getLocation() != null)
                .map(TrafficDetectorDto::from)
                .toList();
        return new TrafficDetectorsDto(matchRadiusMeters, detectors);
    }
}

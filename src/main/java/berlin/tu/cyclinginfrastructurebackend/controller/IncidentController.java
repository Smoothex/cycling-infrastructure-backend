package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.repository.IncidentRepository;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.IncidentDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentRepository incidentRepository;

    public IncidentController(IncidentRepository incidentRepository) {
        this.incidentRepository = incidentRepository;
    }

    /**
     * Near-miss (scary) incidents with their WGS84 positions. {@code from}/{@code to}
     * are optional epoch-ms bounds on the incident timestamp, matching the segment
     * endpoints' year-filter convention.
     */
    @GetMapping("/near-misses")
    public List<IncidentDto> getNearMisses(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        return incidentRepository.findNearMisses(from, to).stream()
                .map(IncidentDto::from)
                .toList();
    }
}

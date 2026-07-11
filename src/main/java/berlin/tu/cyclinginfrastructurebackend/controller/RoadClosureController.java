package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.repository.RoadClosureRepository;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RoadClosureDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/road-closures")
public class RoadClosureController {

    private final RoadClosureRepository roadClosureRepository;

    public RoadClosureController(RoadClosureRepository roadClosureRepository) {
        this.roadClosureRepository = roadClosureRepository;
    }

    /**
     * Road closures and construction sites from the VIZ Berlin feed. {@code from}/{@code to}
     * are optional epoch-ms bounds; a closure is returned when its validity range overlaps
     * the window (open-ended closures overlap everything after their start).
     */
    @GetMapping
    public List<RoadClosureDto> getRoadClosures(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        return roadClosureRepository.findOverlapping(from, to).stream()
                .map(RoadClosureDto::from)
                .toList();
    }
}

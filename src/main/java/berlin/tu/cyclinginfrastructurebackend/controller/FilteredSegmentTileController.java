package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.RideIntent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEnrichmentFilter;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.TrafficCondition;
import berlin.tu.cyclinginfrastructurebackend.service.FilteredSegmentTileService;
import berlin.tu.cyclinginfrastructurebackend.service.dto.SegmentTileFilter;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@RestController
public class FilteredSegmentTileController {
    private final FilteredSegmentTileService service;

    public FilteredSegmentTileController(FilteredSegmentTileService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/segments/tiles/{z}/{x}/{y}.mvt", produces = "application/vnd.mapbox-vector-tile")
    public ResponseEntity<byte[]> tile(@PathVariable int z, @PathVariable int x, @PathVariable int y,
                                      @RequestParam(required = false) Long from,
                                      @RequestParam(required = false) Long to,
                                      @RequestParam(required = false) RideIntent rideIntent,
                                      @RequestParam(required = false) TrafficCondition trafficCondition,
                                      @RequestParam(required = false) List<SegmentEnrichmentFilter> enrichmentFilters) {
        if (z < 6 || z > 14 || x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z)
                || (from != null && to != null && from > to) || (to != null && to < 0 && from == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid tile coordinates or time range");
        }
        var filter = new SegmentTileFilter(from == null ? 0 : from, to == null ? Long.MAX_VALUE : to,
                rideIntent, trafficCondition, enrichmentFilters == null ? Set.of() : Set.copyOf(enrichmentFilters));
        // Revalidate in the server cache so pipeline invalidation is visible without browser cache expiry.
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(service.tile(z, x, y, filter));
    }
}

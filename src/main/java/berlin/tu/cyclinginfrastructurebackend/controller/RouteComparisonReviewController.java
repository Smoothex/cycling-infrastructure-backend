package berlin.tu.cyclinginfrastructurebackend.controller;

import berlin.tu.cyclinginfrastructurebackend.service.RouteComparisonReviewService;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteReviewDetailDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteReviewDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteReviewRequestDto;
import berlin.tu.cyclinginfrastructurebackend.service.dto.api.RouteReviewSampleDto;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/route-comparisons")
public class RouteComparisonReviewController {

    private final RouteComparisonReviewService reviewService;

    public RouteComparisonReviewController(RouteComparisonReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/review-sample")
    public RouteReviewSampleDto getSample() {
        return reviewService.getSample();
    }

    @GetMapping("/review-sample/{rideId}")
    public RouteReviewDetailDto getDetail(@PathVariable UUID rideId) {
        return reviewService.getDetail(rideId);
    }

    @GetMapping("/rides/{rideId}")
    public RouteReviewDetailDto getRideDetail(@PathVariable UUID rideId) {
        return reviewService.getRideDetail(rideId);
    }

    @PutMapping("/review-sample/{rideId}/review")
    public RouteReviewDto saveReview(
            @PathVariable UUID rideId,
            @RequestBody RouteReviewRequestDto request) {
        return reviewService.saveReview(rideId, request);
    }

    @GetMapping("/review-sample.csv")
    public ResponseEntity<byte[]> exportSample() {
        byte[] csv = reviewService.exportSampleCsv().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"route-comparison-review-sample.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}

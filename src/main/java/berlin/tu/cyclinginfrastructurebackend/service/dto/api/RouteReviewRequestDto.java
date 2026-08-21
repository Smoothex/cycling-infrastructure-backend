package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.ManualRouteComparisonClassification;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonReviewIssue;

import java.util.Set;

public record RouteReviewRequestDto(
        ManualRouteComparisonClassification manualClassification,
        Set<RouteComparisonReviewIssue> issueCodes,
        String notes
) {
}

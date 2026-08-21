package berlin.tu.cyclinginfrastructurebackend.service.dto.api;

import berlin.tu.cyclinginfrastructurebackend.domain.RouteComparisonReview;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ManualRouteComparisonClassification;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonReviewIssue;

import java.util.Comparator;
import java.util.List;

public record RouteReviewDto(
        ManualRouteComparisonClassification manualClassification,
        List<RouteComparisonReviewIssue> issueCodes,
        String notes,
        long reviewedAt
) {
    public static RouteReviewDto from(RouteComparisonReview review) {
        return new RouteReviewDto(
                review.getManualClassification(),
                review.getIssueCodes().stream().sorted(Comparator.comparing(Enum::ordinal)).toList(),
                review.getNotes(),
                review.getReviewedAt().toEpochMilli()
        );
    }
}

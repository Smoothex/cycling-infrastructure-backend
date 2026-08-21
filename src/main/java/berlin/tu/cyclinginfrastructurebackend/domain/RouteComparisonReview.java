package berlin.tu.cyclinginfrastructurebackend.domain;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.ManualRouteComparisonClassification;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RouteComparisonReviewIssue;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "route_comparison_reviews", indexes = {
        @Index(name = "idx_route_comparison_review_classification", columnList = "manual_classification"),
        @Index(name = "idx_route_comparison_review_reviewed_at", columnList = "reviewed_at")
})
@Getter
@Setter
@NoArgsConstructor
public class RouteComparisonReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ride_id", nullable = false, unique = true)
    private Ride ride;

    @Enumerated(EnumType.STRING)
    @Column(name = "manual_classification", nullable = false, length = 40)
    private ManualRouteComparisonClassification manualClassification;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "route_comparison_review_issues",
            joinColumns = @JoinColumn(name = "review_id"),
            indexes = @Index(name = "idx_route_comparison_review_issue_review", columnList = "review_id")
    )
    @Enumerated(EnumType.STRING)
    @Column(name = "issue_code", nullable = false, length = 50)
    private Set<RouteComparisonReviewIssue> issueCodes = EnumSet.noneOf(RouteComparisonReviewIssue.class);

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;
}

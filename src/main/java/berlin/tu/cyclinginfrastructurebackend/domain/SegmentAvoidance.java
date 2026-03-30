package berlin.tu.cyclinginfrastructurebackend.domain;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.WindExposure;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Records a single avoidance event: which segment was avoided, by which ride, and when.
 * Provides the temporal dimension needed to correlate avoidances with external factors
 * that are time-bound.
 */
@Entity
@Table(name = "segment_avoidances", indexes = {
        @Index(name = "idx_avoidance_segment", columnList = "segment_id"),
        @Index(name = "idx_avoidance_timestamp", columnList = "avoidedAt")
})
@Getter
@Setter
@NoArgsConstructor
public class SegmentAvoidance {

    private static final ZoneId BERLIN_ZONE = ZoneId.of("Europe/Berlin");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "segment_id", nullable = false)
    private StreetSegment segment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ride_id", nullable = false)
    private Ride ride;

    @Column(nullable = false)
    private Long avoidedAt;

    @Enumerated(EnumType.STRING)
    private DayOfWeek dayOfWeek;

    /** Hour in Berlin local time (0-23), for quick time-of-day aggregation */
    private Integer hourOfDay;

    private boolean weatherEnriched = false;

    @Column(columnDefinition = "boolean not null default false")
    private boolean berlinOpenDataEnriched = false;

    @Column(columnDefinition = "boolean not null default false")
    private boolean ohsomeEnriched = false;

    private String surface;
    private String smoothness;
    private String lit;
    private String highway;
    private Integer maxspeed;
    private String cycleway;
    private Double temperature2m;
    private Double precipitation;
    private Double windSpeed10m;
    private Double windDirection10m;
    private Integer weatherCode;
    private Double shortestPathBearingDegrees;  // direction the rider would have taken on the shortest path
    private Double relativeWindAngleDegrees;

    @Enumerated(EnumType.STRING)
    private WindExposure windExposure;

    public static SegmentAvoidance of(StreetSegment segment, Ride ride, Long avoidedAt) {
        return of(segment, ride, avoidedAt, null);
    }

    public static SegmentAvoidance of(StreetSegment segment,
                                      Ride ride,
                                      Long avoidedAt,
                                      Double shortestPathBearingDegrees) {
        SegmentAvoidance sa = new SegmentAvoidance();
        sa.segment = segment;
        sa.ride = ride;
        sa.avoidedAt = avoidedAt;
        sa.shortestPathBearingDegrees = shortestPathBearingDegrees;

        ZonedDateTime berlinTime = Instant.ofEpochMilli(avoidedAt).atZone(BERLIN_ZONE);
        sa.dayOfWeek = berlinTime.getDayOfWeek();
        sa.hourOfDay = berlinTime.getHour();
        return sa;
    }
}

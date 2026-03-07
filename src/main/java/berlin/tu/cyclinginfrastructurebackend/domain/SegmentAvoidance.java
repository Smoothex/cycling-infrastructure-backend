package berlin.tu.cyclinginfrastructurebackend.domain;

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
    private Instant avoidedAt;

    @Enumerated(EnumType.STRING)
    private DayOfWeek dayOfWeek;

    /** Hour in Berlin local time (0-23), for quick time-of-day aggregation */
    private Integer hourOfDay;

    public static SegmentAvoidance of(StreetSegment segment, Ride ride, Instant avoidedAt) {
        SegmentAvoidance sa = new SegmentAvoidance();
        sa.segment = segment;
        sa.ride = ride;
        sa.avoidedAt = avoidedAt;

        ZonedDateTime berlinTime = avoidedAt.atZone(BERLIN_ZONE);
        sa.dayOfWeek = berlinTime.getDayOfWeek();
        sa.hourOfDay = berlinTime.getHour();
        return sa;
    }
}


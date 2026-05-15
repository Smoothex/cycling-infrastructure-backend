package berlin.tu.cyclinginfrastructurebackend.domain;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayLocation;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.CyclewayType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RideIntent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.SegmentEventType;
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
 * Records a single segment event: either an avoidance (segment was on the shortest path but
 * the cyclist bypassed it) or a preference (segment was NOT on the shortest path but the
 * cyclist chose it anyway).
 */
@Entity
@Table(name = "segment_events", indexes = {
        @Index(name = "idx_event_segment", columnList = "segment_id"),
        @Index(name = "idx_event_timestamp", columnList = "eventTimestamp"),
        @Index(name = "idx_event_type", columnList = "eventType"),
        @Index(name = "idx_cycleway_type", columnList = "cyclewayType")
})
@Getter
@Setter
@NoArgsConstructor
public class SegmentEvent {

    private static final ZoneId BERLIN_ZONE = ZoneId.of("Europe/Berlin");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SegmentEventType eventType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "segment_id", nullable = false)
    private StreetSegment segment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ride_id", nullable = false)
    private Ride ride;

    @Column(nullable = false)
    private Long eventTimestamp;

    @Enumerated(EnumType.STRING)
    private DayOfWeek dayOfWeek;

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
    
    @Enumerated(EnumType.STRING)
    private CyclewayType cyclewayType;
    
    @Enumerated(EnumType.STRING)
    private CyclewayLocation cyclewayLocation;
    
    private String cyclewaySurface;
    private Double cyclewayWidth;
    private Boolean bicycleOneway;
    
    private Double temperature2m;
    private Double precipitation;
    private Double windSpeed10m;
    private Double windDirection10m;
    private Integer weatherCode;
    private Double pathBearingDegrees;
    private Double relativeWindAngleDegrees;

    @Enumerated(EnumType.STRING)
    private WindExposure windExposure;

    @Enumerated(EnumType.STRING)
    private RideIntent rideIntent;

    public static SegmentEvent of(SegmentEventType type,
                                  StreetSegment segment,
                                  Ride ride,
                                  Long eventTimestamp,
                                  Double pathBearingDegrees) {
        SegmentEvent event = new SegmentEvent();
        event.eventType = type;
        event.segment = segment;
        event.ride = ride;
        event.eventTimestamp = eventTimestamp;
        event.pathBearingDegrees = pathBearingDegrees;
        event.rideIntent = ride.getRideIntent();

        ZonedDateTime berlinTime = Instant.ofEpochMilli(eventTimestamp).atZone(BERLIN_ZONE);
        event.dayOfWeek = berlinTime.getDayOfWeek();
        event.hourOfDay = berlinTime.getHour();
        return event;
    }
}

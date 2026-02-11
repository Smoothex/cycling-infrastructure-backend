package berlin.tu.cyclinginfrastructurebackend.domain;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.IncidentType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.ParticipantType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.locationtech.jts.geom.Point;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "incidents")
@Getter
@Setter
@NoArgsConstructor
public class Incident {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ride_id")
    private Ride ride;

    private Integer incidentKey; // key in csv

    @Column(columnDefinition = "geometry(Point,4326)")
    private Point location;

    private Long timestamp;

    @Enumerated(EnumType.STRING)
    private IncidentType incidentType;

    private Boolean scary;

    @Column(length = 1000)
    private String description;

    @ElementCollection(targetClass = ParticipantType.class)
    @CollectionTable(name = "incident_participants", joinColumns = @JoinColumn(name = "incident_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "participant_type")
    private Set<ParticipantType> involvedParticipants = new HashSet<>();

}

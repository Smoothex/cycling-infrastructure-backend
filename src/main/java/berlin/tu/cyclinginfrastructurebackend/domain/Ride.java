package berlin.tu.cyclinginfrastructurebackend.domain;

import berlin.tu.cyclinginfrastructurebackend.domain.enums.BikeType;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.PhoneLocation;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.RideIntent;
import berlin.tu.cyclinginfrastructurebackend.domain.enums.Status;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.locationtech.jts.geom.LineString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "rides")
@Getter
@Setter
@NoArgsConstructor
public class Ride {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    private BikeType bikeType;

    private Boolean childTransport;
    private Boolean trailerAttached;

    @Enumerated(EnumType.STRING)
    private PhoneLocation phoneLocation;

    @OneToMany(mappedBy = "ride", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RidePoint> ridePoints = new ArrayList<>();

    @OneToMany(mappedBy = "ride", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Incident> incidents = new ArrayList<>();

    private String originalFilename;

    private Long startTime;
    private Long endTime;

    @Column(columnDefinition = "geometry(LineString,4326)")
    private LineString trajectory;

    @ElementCollection
    @CollectionTable(name = "ride_edges", joinColumns = @JoinColumn(name = "ride_id"))
    @Column(name = "edge_id")
    private List<Integer> traversedEdgeIds = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    private Double actualDistance;
    private Double shortestPathDistance;
    private Boolean isDetour;
    private Double overlapRatio;    // actual overlap with shortest path

    @Enumerated(EnumType.STRING)
    private RideIntent rideIntent;

    @Column(columnDefinition = "geometry(LineString,4326)")
    private LineString shortestPath;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "ride_shortest_path_edges",
            joinColumns = @JoinColumn(name = "ride_id"),
            indexes = @Index(name = "idx_shortest_path_edge_id", columnList = "edge_id")
    )
    @Column(name = "edge_id")
    private List<Integer> shortestPathEdgeIds = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "ride_edge_bearings",
            joinColumns = @JoinColumn(name = "ride_id"),
            indexes = @Index(name = "idx_ride_edge_bearing_edge_id", columnList = "edge_id")
    )
    @MapKeyColumn(name = "edge_id")
    @Column(name = "bearing_degrees")
    private Map<Integer, Double> traversedEdgeBearings = new HashMap<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "ride_edge_timestamps",
            joinColumns = @JoinColumn(name = "ride_id"),
            indexes = @Index(name = "idx_ride_edge_timestamp_edge_id", columnList = "edge_id")
    )
    @MapKeyColumn(name = "edge_id")
    @Column(name = "timestamp")
    private Map<Integer, Long> traversedEdgeTimestamps = new HashMap<>();

}

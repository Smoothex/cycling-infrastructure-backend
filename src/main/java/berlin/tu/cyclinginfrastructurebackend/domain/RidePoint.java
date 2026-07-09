package berlin.tu.cyclinginfrastructurebackend.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.locationtech.jts.geom.Point;

import java.util.UUID;

@Entity
@Table(name = "ride_points", indexes = @Index(name = "idx_ride_points_ride_id", columnList = "ride_id"))
@Getter
@Setter
@NoArgsConstructor
public class RidePoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ride_id")
    private Ride ride;

    @Column(columnDefinition = "geometry(Point,4326)")
    private Point location;

    private Long timestamp;

    // Accelerometer raw
    private Double x;
    private Double y;
    private Double z;

    private Double gpsAccuracy; // 'acc' in raw data

    // Gyroscope
    private Double a;
    private Double b;
    private Double c;

    // To maintain sequence in the list if timestamps are same
    private Integer sequenceIndex;
}

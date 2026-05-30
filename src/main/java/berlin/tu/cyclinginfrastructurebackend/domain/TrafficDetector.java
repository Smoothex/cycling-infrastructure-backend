package berlin.tu.cyclinginfrastructurebackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.locationtech.jts.geom.Point;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "traffic_detectors", indexes = {
        @Index(name = "idx_traffic_detector_det_id15", columnList = "detId15"),
        @Index(name = "idx_traffic_detector_det_name_alt", columnList = "detNameAlt"),
        @Index(name = "idx_traffic_detector_mq", columnList = "mqKurzname"),
        @Index(name = "idx_traffic_detector_street", columnList = "street")
})
@Getter
@Setter
@NoArgsConstructor
public class TrafficDetector {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String detId15;

    @Column(unique = true)
    private String detNameAlt;

    private String detNameNeu;
    private String mqKurzname;
    private String mqId15;
    private String street;
    private String position;
    private String positionDetail;
    private String direction;
    private String lane;

    @Column(columnDefinition = "geometry(Point, 4326)")
    private Point location;

    private LocalDate activeFrom;
    private LocalDate activeTo;
    private Boolean deinstalled;
}

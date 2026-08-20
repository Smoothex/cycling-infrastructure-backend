package berlin.tu.cyclinginfrastructurebackend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Persistent 0.1 degree Open-Meteo grid assignment for a street segment. */
@Entity
@Table(name = "open_meteo_segment_grid", indexes = {
        @Index(name = "idx_open_meteo_segment_grid_location",
                columnList = "latitude_tenths,longitude_tenths")
})
@Getter
@Setter
@NoArgsConstructor
public class OpenMeteoSegmentGrid {

    @Id
    @Column(name = "segment_id", nullable = false)
    private Long segmentId;

    @Column(name = "latitude_tenths", nullable = false)
    private Integer latitudeTenths;

    @Column(name = "longitude_tenths", nullable = false)
    private Integer longitudeTenths;
}
